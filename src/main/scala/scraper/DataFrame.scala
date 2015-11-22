package scraper

import scraper.expressions.dsl._
import scraper.expressions.functions._
import scraper.expressions.{Expression, NamedExpression, Predicate}
import scraper.plans.logical.LogicalPlan
import scraper.plans.{QueryExecution, logical}
import scraper.types.TupleType

class DataFrame(val queryExecution: QueryExecution) {
  def this(logicalPlan: LogicalPlan, context: Context) = this(context execute logicalPlan)

  def context: Context = queryExecution.context

  private def build(f: LogicalPlan => LogicalPlan): DataFrame =
    new DataFrame(f(queryExecution.logicalPlan), context)

  lazy val schema: TupleType = TupleType fromAttributes queryExecution.analyzedPlan.output

  def rename(newNames: String*): DataFrame = {
    assert(newNames.length == schema.fields.length)
    val oldNames = schema.fields map (_.name)
    val aliases = (oldNames, newNames).zipped map { Symbol(_) as _ }
    this select aliases
  }

  def select(first: NamedExpression, rest: NamedExpression*): DataFrame =
    this select (first +: rest)

  def select(expressions: Seq[NamedExpression]): DataFrame = build(logical.Project(_, expressions))

  def filter(condition: Predicate): DataFrame = build(logical.Filter(_, condition))

  def where(condition: Predicate): DataFrame = this filter condition

  def limit(n: Expression): DataFrame = build(logical.Limit(_, n))

  def limit(n: Int): DataFrame = this limit lit(n)

  def iterator: Iterator[Row] = queryExecution.physicalPlan.iterator

  def registerAsTable(tableName: String): Unit =
    context.catalog.registerRelation(tableName, queryExecution.analyzedPlan)

  def toSeq: Seq[Row] = iterator.toSeq

  def explanation(extended: Boolean): String = if (extended) {
    s"""# Logical plan
       |${queryExecution.logicalPlan.prettyTree}
       |
       |# Analyzed plan
       |${queryExecution.analyzedPlan.prettyTree}
       |
       |# Optimized plan
       |${queryExecution.optimizedPlan.prettyTree}
       |
       |# Physical plan
       |${queryExecution.physicalPlan.prettyTree}
     """.stripMargin
  } else {
    s"""# Physical plan
       |${queryExecution.physicalPlan.prettyTree}
     """.stripMargin
  }
}
