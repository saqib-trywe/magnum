package com.augustnagro.magnum

import scala.deriving.Mirror
import scala.compiletime.{
  constValue,
  constValueTuple,
  erasedValue,
  error,
  summonInline
}
import scala.compiletime.ops.any.==
import scala.compiletime.ops.boolean.&&
import scala.reflect.ClassTag
import scala.quoted.*

sealed trait DbSchema[EC, E, ID] extends Selectable:
  def selectDynamic(scalaName: String): Any
  def all: IArray[DbSchemaName]
  def alias: String
  def alias(tableSqlAlias: String): this.type

  private[magnum] def tableWithAlias: String
  private[magnum] def count(using DbCon): Long
  private[magnum] def existsById(id: ID)(using DbCon): Boolean
  private[magnum] def findAll(using DbCon): Vector[E]
  private[magnum] def findAll(spec: Spec[E])(using DbCon): Vector[E]
  private[magnum] def findById(id: ID)(using DbCon): Option[E]
  private[magnum] def findAllById(ids: Iterable[ID])(using DbCon): Vector[E]
  private[magnum] def deleteById(id: ID)(using DbCon): Unit
  private[magnum] def truncate()(using DbCon): Unit
  private[magnum] def deleteAllById(ids: Iterable[ID])(using DbCon): Unit
  private[magnum] def insert(entityCreator: EC)(using DbCon): E
  private[magnum] def insertAll(entityCreators: Iterable[EC])(using
      DbCon
  ): Vector[E]
  private[magnum] def update(entity: E)(using DbCon): Unit
  private[magnum] def updateAll(entities: Iterable[E])(using DbCon): Unit

object DbSchema:
  transparent inline def apply[EC <: Product, E <: Product, ID](
      sqlNameMapper: SqlNameMapper = SameCase
  )(using
      ecMirror: Mirror.ProductOf[EC],
      eMirror: Mirror.ProductOf[E],
      dbEntity: DbReader[E],
      idCls: ClassTag[ID]
  ) = ${ dbSchemaImpl[EC, E, ID]('{ sqlNameMapper }) }

  private def dbSchemaImpl[EC: Type, E: Type, ID: Type](
      sqlNameMapper: Expr[SqlNameMapper]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*
    Expr.summon[Mirror.ProductOf[E]].get match
      case '{
            $m: Mirror.ProductOf[E] {
              type MirroredElemLabels = mels
              type MirroredLabel = tableName
            }
          } =>
        val tableName = Expr(Type.valueOfConstant[tableName].get.toString)
        val tableNameSql = '{ $sqlNameMapper.toTableName($tableName) }
        applyRefinements[EC, E, ID, mels, DbSchema[EC, E, ID]](
          sqlNameMapper,
          tableNameSql,
          Nil
        )

  private def applyRefinements[
      EC: Type,
      E: Type,
      ID: Type,
      EMels: Type,
      RES: Type
  ](
      sqlNameMapper: Expr[SqlNameMapper],
      tableNameSql: Expr[String],
      fieldNames: List[String]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*
    Type.of[EMels] match
      case '[mel *: melTail] =>
        val scalaFieldName = Type.valueOfConstant[mel].get.toString
        val fieldNameRefinement =
          Refinement(
            TypeRepr.of[RES],
            scalaFieldName,
            TypeRepr.of[DbSchemaName]
          )
        fieldNameRefinement.asType match
          case '[tpe] =>
            applyRefinements[EC, E, ID, melTail, tpe](
              sqlNameMapper,
              tableNameSql,
              scalaFieldName :: fieldNames
            )
      case '[EmptyTuple] =>
        buildDbSchema[EC, E, ID, RES](
          tableNameSql,
          Expr(fieldNames),
          sqlNameMapper
        )

  private def buildDbSchema[EC: Type, E: Type, ID: Type, RES: Type](
      tableNameSql: Expr[String],
      fieldNames: Expr[List[String]],
      sqlNameMapper: Expr[SqlNameMapper]
  )(using Quotes): Expr[Any] =
    val dbReaderExpr = Expr.summon[DbReader[E]].get
    val idClassTag = Expr.summon[ClassTag[ID]].get
    '{
      given DbReader[E] = $dbReaderExpr
      given ClassTag[ID] = $idClassTag
      val nameMapper: SqlNameMapper = $sqlNameMapper
      val tblNameSql: String = $tableNameSql

      val defaultAlias: String =
        if tblNameSql.length == 1 then tblNameSql.toLowerCase
        else tblNameSql.updated(0, tblNameSql.head.toLower)

      val schemaNames: IArray[DbSchemaName] = IArray.from(
        $fieldNames.iterator
          .map(fn =>
            DbSchemaName(
              scalaName = fn,
              sqlName = nameMapper.toColumnName(fn),
              tableAlias = defaultAlias
            )
          )
      )

      // todo make DbSchema a class with these parameters instead..
      class DbSchemaImpl(
          tableAlias: String,
          schemaNames: IArray[DbSchemaName],
          idName: DbSchemaName
      ) extends DbSchema[EC, E, ID]:

        def selectDynamic(scalaName: String): Any =
          schemaNames.find(_.scalaName == scalaName).get

        def all: IArray[DbSchemaName] = schemaNames

        def alias: String = tableAlias

        def alias(tableAlias: String): this.type =
          val newSchemaNames =
            schemaNames.map(sn => sn.copy(tableAlias = tableAlias))
          new DbSchemaImpl(
            tableAlias,
            newSchemaNames,
            newSchemaNames(0)
          ).asInstanceOf[this.type]

        def tableWithAlias: String =
          tblNameSql + " " + tableAlias

        def count(using con: DbCon): Long =
          sql"select count(*) from $this".run[Long].head

        def existsById(id: ID)(using DbCon): Boolean =
          sql"select 1 from $this where $idName = $id".run[Int].nonEmpty

        def findAll(using DbCon): Vector[E] =
          sql"select * from $this".run

        def findAll(spec: Spec[E])(using DbCon): Vector[E] = ???

        def findById(id: ID)(using DbCon): Option[E] =
          sql"select * from $this where $idName = $id".run[E].headOption

        def findAllById(ids: Iterable[ID])(using DbCon): Vector[E] =
          sql"select * from $this where $idName = ANY(${ids.toArray})".run

        def deleteById(id: ID)(using DbCon): Unit = ???
        def truncate()(using DbCon): Unit = ???
        def deleteAllById(ids: Iterable[ID])(using DbCon): Unit = ???
        def insert(entityCreator: EC)(using DbCon): E = ???
        def insertAll(entityCreators: Iterable[EC])(using DbCon): Vector[E] =
          ???
        def update(entity: E)(using DbCon): Unit = ???
        def updateAll(entities: Iterable[E])(using DbCon): Unit = ???
      end DbSchemaImpl

      DbSchemaImpl(defaultAlias, schemaNames, schemaNames.last)
        .asInstanceOf[RES]
    }