package io.radicalbit.nsdb.sql.parser

import io.radicalbit.nsdb.common.JSerializable
import io.radicalbit.nsdb.common.statement._

import scala.util.parsing.combinator.{PackratParsers, RegexParsers}
import scala.util.{Try, Failure => ScalaFailure, Success => ScalaSuccess}
import scala.language.postfixOps
import scala.util.parsing.input.CharSequenceReader

final class SQLStatementParser extends RegexParsers with PackratParsers {

  implicit class InsensitiveString(str: String) {
    def ignoreCase: PackratParser[String] = ("""(?i)\Q""" + str + """\E""").r ^^ { _.toString.toUpperCase }
  }

  private val Insert           = "INSERT INTO" ignoreCase
  private val Dim              = "DIM" ignoreCase
  private val Ts               = "TS" ignoreCase
  private val Val              = "VAL" ignoreCase
  private val Select           = "SELECT" ignoreCase
  private val Delete           = "DELETE" ignoreCase
  private val Drop             = "Drop" ignoreCase
  private val All              = "*"
  private val From             = "FROM" ignoreCase
  private val Where            = "WHERE" ignoreCase
  private val Comma            = ","
  private val In               = "IN" ignoreCase
  private val Order            = "ORDER BY" ignoreCase
  private val Desc             = "DESC" ignoreCase
  private val Limit            = "LIMIT" ignoreCase
  private val GreaterThan      = ">"
  private val GreaterOrEqualTo = ">="
  private val LessThan         = "<"
  private val LessOrEqualTo    = "<="
  private val Equal            = "="
  private val Not              = "NOT" ignoreCase
  private val And              = "AND" ignoreCase
  private val Or               = "OR" ignoreCase
  private val now              = "NOW" ignoreCase
  private val sum = "SUM".ignoreCase ^^ { _ =>
    SumAggregation
  }
  private val min = "MIN".ignoreCase ^^ { _ =>
    MinAggregation
  }
  private val max = "MAX".ignoreCase ^^ { _ =>
    MaxAggregation
  }
  private val count = "COUNT".ignoreCase ^^ { _ =>
    CountAggregation
  }
  private val group             = "GROUP BY" ignoreCase
  private val OpenRoundBracket  = "("
  private val CloseRoundBracket = ")"

  private val digits     = """(^(?!now)[a-zA-Z_][a-zA-Z0-9_]*)""".r
  private val numbers    = """([0-9]+)""".r
  private val intValue   = numbers ^^ { _.toInt }
  private val longValue  = numbers ^^ { _.toLong }
  private val floatValue = """([0-9]+)\.([0-9]+)""".r ^^ { _.toDouble }

  private val field = digits ^^ { e =>
    Field(e, None)
  }
  private val aggField = ((sum | min | max | count) <~ OpenRoundBracket) ~ digits <~ CloseRoundBracket ^^ { e =>
    Field(e._2, Some(e._1))
  }
  private val metric      = """(^[a-zA-Z][a-zA-Z0-9_]*)""".r
  private val dimension   = digits
  private val stringValue = digits

  private val timeMeasure = ("h".ignoreCase | "m".ignoreCase | "s".ignoreCase).map(_.toUpperCase()) ^^ {
    case "H" => 3600 * 1000
    case "M" => 60 * 1000
    case "S" => 1000
  }

  private val delta = now ~> ("+" | "-") ~ longValue ~ timeMeasure ^^ {
    case "+" ~ v ~ measure => System.currentTimeMillis() + v * measure
    case "-" ~ v ~ measure => System.currentTimeMillis() - v * measure
  }

  private val timestamp = delta | longValue

  private val selectFields = (All | aggField | field) ~ rep(Comma ~> field) ^^ {
    case f ~ fs =>
      f match {
        case All      => AllFields
        case f: Field => ListFields(f +: fs)
      }
  }

  private val timestampAssignment = (Ts ~ Equal) ~> timestamp

  private val valueAssignment = (Val ~ Equal) ~> (floatValue | longValue)

  private val assignment = (dimension <~ Equal) ~ (stringValue | floatValue | intValue) ^^ {
    case k ~ v => k -> v.asInstanceOf[JSerializable]
  }

  private val assignments = OpenRoundBracket ~> assignment ~ rep(Comma ~> assignment) <~ CloseRoundBracket ^^ {
    case a ~ as => (a +: as).toMap
  }

  // Please don't change the order of the expressions, can cause infinite recursions
  private lazy val expression: PackratParser[Expression] =
    rangeExpression | unaryLogicalExpression | tupledLogicalExpression | comparisonExpression | equalityExpression

  private lazy val termExpression
    : PackratParser[Expression] = rangeExpression | comparisonExpression | equalityExpression

  private lazy val unaryLogicalExpression = notUnaryLogicalExpression

  private lazy val notUnaryLogicalExpression: PackratParser[UnaryLogicalExpression] =
    (Not ~> expression) ^^ (expression => UnaryLogicalExpression(expression, NotOperator))

  private lazy val tupledLogicalExpression: PackratParser[TupledLogicalExpression] =
    andTupledLogicalExpression | orTupledLogicalExpression

  private def tupledLogicalExpression(operator: PackratParser[String],
                                      tupledOperator: TupledLogicalOperator): PackratParser[TupledLogicalExpression] =
    ((termExpression | expression) <~ operator) ~ (termExpression | expression) ^^ {
      case expression1 ~ expression2 =>
        TupledLogicalExpression(expression1, tupledOperator, expression2)
    }

  lazy val andTupledLogicalExpression: PackratParser[TupledLogicalExpression] =
    tupledLogicalExpression(And, AndOperator)

  lazy val orTupledLogicalExpression: PackratParser[TupledLogicalExpression] = tupledLogicalExpression(Or, OrOperator)

  lazy val equalityExpression
    : PackratParser[EqualityExpression[Any]] = (dimension <~ Equal) ~ (stringValue | floatValue | timestamp) ^^ {
    case dim ~ v => EqualityExpression(dim, v)
  }

  lazy val comparisonExpression: PackratParser[ComparisonExpression[_]] =
    comparisonExpressionGT | comparisonExpressionGTE | comparisonExpressionLT | comparisonExpressionLTE

  private def comparisonExpression(operator: String,
                                   comparisonOperator: ComparisonOperator): PackratParser[ComparisonExpression[_]] =
    (dimension <~ operator) ~ timestamp ^^ {
      case d ~ v =>
        ComparisonExpression(d, comparisonOperator, v)
    }

  private lazy val comparisonExpressionGT = comparisonExpression(GreaterThan, GreaterThanOperator)

  private lazy val comparisonExpressionGTE = comparisonExpression(GreaterOrEqualTo, GreaterOrEqualToOperator)

  private lazy val comparisonExpressionLT = comparisonExpression(LessThan, LessThanOperator)

  private lazy val comparisonExpressionLTE = comparisonExpression(LessOrEqualTo, LessOrEqualToOperator)

  private lazy val rangeExpression =
    (dimension <~ In) ~ (OpenRoundBracket ~> timestamp) ~ (Comma ~> timestamp <~ CloseRoundBracket) ^^ {
      case (d ~ v1 ~ v2) => RangeExpression(dimension = d, value1 = v1, value2 = v2)
    }

  lazy val select: PackratParser[SelectedFields with Product with Serializable] = Select ~> selectFields

  lazy val from: PackratParser[String] = From ~> metric

  lazy val where: PackratParser[Expression] = Where ~> expression

  lazy val groupBy: PackratParser[Option[String]] = (group ~> dimension) ?

  lazy val order: PackratParser[Option[OrderOperator]] = ((Order ~> dimension ~ (Desc ?)) ?) ^^ {
    case Some(dim ~(Some(_))) => Some(DescOrderOperator(dim))
    case Some(dim ~ None)     => Some(AscOrderOperator(dim))
    case None                 => None
  }

  lazy val limit: PackratParser[Option[LimitOperator]] = ((Limit ~> intValue) ?) ^^ (value =>
    value.map(x => LimitOperator(x)))

  private def selectQuery(namespace: String) = select ~ from ~ (where ?) ~ groupBy ~ order ~ limit <~ ";" ^^ {
    case fs ~ met ~ cond ~ gr ~ ord ~ lim =>
      SelectSQLStatement(namespace = namespace,
                         metric = met,
                         fields = fs,
                         condition = cond.map(Condition),
                         groupBy = gr,
                         order = ord,
                         limit = lim)
  }

  private def deleteQuery(namespace: String) = Delete ~> from ~ where ^^ {
    case met ~ cond => DeleteSQLStatement(namespace = namespace, metric = met, condition = Condition(cond))
  }

  private def dropStatement(namespace: String) = Drop ~> metric ^^ { met =>
    DropSQLStatement(namespace = namespace, metric = met)
  }

  private def insertQuery(namespace: String) =
    (Insert ~> metric) ~
      (timestampAssignment ?) ~
      (Dim ~> assignments ?) ~ valueAssignment ^^ {
      case met ~ ts ~ dimensions ~ value =>
        InsertSQLStatement(namespace = namespace,
                           metric = met,
                           timestamp = ts,
                           dimensions.map(ListAssignment),
                           value.asInstanceOf[JSerializable])
    }

  private def query(namespace: String): PackratParser[SQLStatement] =
    selectQuery(namespace) | insertQuery(namespace) | deleteQuery(namespace) | dropStatement(namespace)

  def parse(namespace: String, input: String): Try[SQLStatement] =
    Try(parse(query(namespace), new PackratReader[Char](new CharSequenceReader(s"$input;")))) flatMap {
      case Success(res, _) => ScalaSuccess(res)
      case Error(msg, next) =>
        ScalaFailure(new RuntimeException(s"$msg \n ${next.source.toString.takeRight(next.offset)}"))
      case Failure(msg, next) =>
        ScalaFailure(new RuntimeException(s"$msg \n ${next.source.toString.takeRight(next.offset)}"))
    }
}
