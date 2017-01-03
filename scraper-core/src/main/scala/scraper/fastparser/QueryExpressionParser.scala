package scraper.fastparser

import fastparse.all._

import scraper.Name
import scraper.expressions.{AutoAlias, Expression, NamedExpression, SortOrder, Star}
import scraper.plans.logical._

// SQL06 section 7.6
object TableReferenceParser {
  import IdentifierParser._
  import KeywordParser._
  import NameParser._
  import SubqueryParser._
  import WhitespaceApi._

  private val correlationClause: P[LogicalPlan => LogicalPlan] = (
    AS.? ~ identifier
    map { name => (_: LogicalPlan) subquery name }
    opaque "correlation-clause"
  )

  private val tableOrQueryName: P[LogicalPlan] =
    tableName map { _.table } map table opaque "table-or-query-name"

  private val derivedTable: P[LogicalPlan] =
    tableSubquery opaque "derived-table"

  private val tablePrimary: P[LogicalPlan] = (
    tableOrQueryName ~ correlationClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    | derivedTable ~ correlationClause
    map { case (name, f) => f(name) }
    opaque "table-primary"
  )

  private val tableFactor: P[LogicalPlan] =
    tablePrimary opaque "table-factor"

  val columnNameList: P[Seq[Name]] =
    columnName rep (min = 1, sep = ",") opaque "column-name-list"

  val tableReference: P[LogicalPlan] =
    tableFactor opaque "table-reference"
}

// SQL06 section 7.9
object GroupByClauseParser {
  import ColumnReferenceParser._
  import KeywordParser._
  import ValueExpressionParser._
  import WhitespaceApi._

  private val groupingColumnReference: P[Expression] =
    columnReference | valueExpression opaque "grouping-column-reference"

  private val groupingColumnReferenceList: P[Seq[Expression]] =
    groupingColumnReference rep (min = 1, sep = ",") opaque "grouping-column-reference-list"

  private val ordinaryGroupingSet: P[Seq[Expression]] = (
    groupingColumnReference.map { _ :: Nil }
    | "(" ~ groupingColumnReferenceList ~ ")"
    opaque "ordinary-grouping-set"
  )

  private val groupingElement: P[Seq[Expression]] =
    ordinaryGroupingSet opaque "grouping-element"

  private val groupingElementList: P[Seq[Expression]] = (
    groupingElement
    rep (min = 1, sep = ",")
    map { _.flatten }
    opaque "grouping-element-list"
  )

  val groupByClause: P[Seq[Expression]] =
    GROUP ~ BY ~ groupingElementList opaque "group-by-clause"
}

// SQL06 section 7.12
object QuerySpecificationParser {
  import AggregateFunctionParser._
  import BooleanValueExpressionParser._
  import GroupByClauseParser._
  import IdentifierParser._
  import KeywordParser._
  import NameParser._
  import SymbolParser._
  import TableReferenceParser._
  import ValueExpressionParser._
  import WhitespaceApi._

  private val asClause: P[Name] =
    AS.? ~ columnName opaque "as-clause"

  private val derivedColumn: P[NamedExpression] = (
    (valueExpression ~ asClause).map { case (e, a) => e as a }
    | valueExpression.map { AutoAlias.named }
    opaque "derived-column"
  )

  private val asteriskedIdentifier: P[Name] =
    identifier opaque "asterisked-identifier"

  private val asteriskedIdentifierChain: P[Seq[Name]] =
    asteriskedIdentifier rep (min = 1, sep = ".") opaque "asterisked-identifier-chain"

  val qualifiedAsterisk: P[NamedExpression] = (
    asteriskedIdentifierChain ~ "." ~ `*`
    map { case Seq(qualifier) => Star(Some(qualifier)) }
    opaque "qualified-asterisk"
  )

  private val selectSublist: P[NamedExpression] = (
    qualifiedAsterisk
    | derivedColumn
    opaque "select-sublist"
  )

  val asterisk: P[Star] = `*` attach Star(None)

  private val selectList: P[Seq[NamedExpression]] = (
    asterisk.map { _ :: Nil }
    | selectSublist.rep(min = 1, sep = ",")
    opaque "select-list"
  )

  private val tableReferenceList: P[LogicalPlan] = (
    tableReference
    rep (min = 1, sep = ",")
    map { _ reduce (_ join _) }
    opaque "table-reference-list"
  )

  private val fromClause: P[LogicalPlan] =
    FROM ~ tableReferenceList opaque "from-clause"

  private val searchCondition: P[Expression] =
    booleanValueExpression opaque "search-condition"

  private val whereClause: P[LogicalPlan => LogicalPlan] = (
    WHERE ~ searchCondition
    map { cond => (_: LogicalPlan) filter cond }
    opaque "where-clause"
  )

  private val havingClause: P[LogicalPlan => LogicalPlan] = (
    HAVING ~ searchCondition
    map { cond => (_: LogicalPlan) filter cond }
    opaque "having-clause"
  )

  private val orderingSpecification: P[Expression => SortOrder] = (
    ASC.attach { (_: Expression).asc }
    | DESC.attach { (_: Expression).desc }
    opaque "ordering-specification"
  )

  private val nullOrdering: P[SortOrder => SortOrder] =
    NULLS ~ (
      FIRST.attach { (_: SortOrder).nullsFirst }
      | LAST.attach { (_: SortOrder).nullsLast }
    ) opaque "null-ordering"

  private val sortKey: P[Expression] =
    valueExpression opaque "sort-key"

  private val sortSpecification: P[SortOrder] = (
    sortKey
    ~ orderingSpecification.?.map { _ getOrElse { (_: Expression).asc } }
    ~ nullOrdering.?.map { _ getOrElse { (_: SortOrder).nullsLarger } }
    map { case (key, f, g) => f andThen g apply key }
    opaque "sort-specification"
  )

  private val sortSpecificationList: P[Seq[SortOrder]] =
    sortSpecification rep (min = 1, sep = ",") opaque "sort-specification-list"

  private val orderByClause: P[LogicalPlan => LogicalPlan] = (
    ORDER ~ BY
    ~ sortSpecificationList map { sortOrders => (_: LogicalPlan) orderBy sortOrders }
    opaque "order-by-clause"
  )

  val querySpecification: P[LogicalPlan] = (
    SELECT
    ~ setQuantifier.?.map { _ getOrElse identity[LogicalPlan] _ }
    ~ selectList
    ~ fromClause.?.map { _ getOrElse SingleRowRelation }
    ~ whereClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    ~ groupByClause.?
    ~ havingClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    ~ orderByClause.?.map { _ getOrElse identity[LogicalPlan] _ } map {
      case (quantify, projectList, relation, filter, maybeGroups, having, orderBy) =>
        val projectOrAgg = maybeGroups map { groups =>
          (_: LogicalPlan) groupBy groups agg projectList
        } getOrElse {
          (_: LogicalPlan) select projectList
        }

        filter andThen projectOrAgg andThen quantify andThen having andThen orderBy apply relation
    }
    opaque "query-specification"
  )
}

// SQL06 section 7.13
object QueryExpressionParser {
  import KeywordParser._
  import NameParser._
  import QuerySpecificationParser._
  import TableReferenceParser._
  import WhitespaceApi._

  private val withColumnList: P[Seq[Name]] =
    columnNameList opaque "with-column-list"

  private val withListElement: P[LogicalPlan => LogicalPlan] =
    queryName ~ ("(" ~ withColumnList ~ ")").? ~ AS ~ "(" ~ P(queryExpression) ~ ")" map {
      case (name, maybeColumns, query) =>
        (child: LogicalPlan) => With(child, name, query, maybeColumns)
    } opaque "with-list-element"

  private val withList: P[LogicalPlan => LogicalPlan] = (
    withListElement rep (min = 1, sep = ",")
    map { _ reduce { _ andThen _ } }
    opaque "with-list"
  )

  private val withClause: P[LogicalPlan => LogicalPlan] =
    WITH ~ withList opaque "with-clause"

  private val simpleTable: P[LogicalPlan] =
    querySpecification opaque "query-specification"

  private val queryPrimary: P[LogicalPlan] = (
    "(" ~ P(queryExpressionBody) ~ ")"
    | simpleTable
    opaque "query-primary"
  )

  private val queryTerm: P[LogicalPlan] =
    queryPrimary chain (INTERSECT attach Intersect) opaque "query-term"

  private lazy val queryExpressionBody: P[LogicalPlan] =
    queryTerm chain (
      (UNION ~ ALL.? attach Union) | (EXCEPT attach Except)
    ).~/ opaque "query-expression-body"

  lazy val queryExpression: P[LogicalPlan] = (
    withClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    ~ queryExpressionBody map { case (f, query) => f apply query }
    opaque "query-expression"
  )
}

// SQL06 section 7.15
object SubqueryParser {
  import QueryExpressionParser._

  private val subquery: P[LogicalPlan] = "(" ~ P(queryExpression) ~ ")" opaque "subquery"

  val tableSubquery: P[LogicalPlan] = subquery opaque "table-subquery"
}