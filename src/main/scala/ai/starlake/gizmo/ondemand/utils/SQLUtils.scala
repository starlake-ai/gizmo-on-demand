package ai.starlake.gizmo.ondemand.utils

import com.typesafe.scalalogging.StrictLogging
import net.sf.jsqlparser.parser.{CCJSqlParser, CCJSqlParserUtil}
import net.sf.jsqlparser.statement.select.{PlainSelect, Select, SelectVisitorAdapter, SetOperationList}
import net.sf.jsqlparser.statement.{Statement, StatementVisitorAdapter}
import net.sf.jsqlparser.util.TablesNamesFinder

import java.util.function.Consumer
import scala.jdk.CollectionConverters.*

object SQLUtils extends  StrictLogging {
  def jsqlParse(sql: String): Statement = {
    val features = new Consumer[CCJSqlParser] {
      override def accept(t: CCJSqlParser): Unit = {
        t.withTimeOut(60 * 1000)
      }
    }
    try {
      CCJSqlParserUtil.parse(sql.trim, features)
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to parse $sql")
        throw exception
    }
  }
  def unquoteAgressive(cols: List[String]): List[String] = {
    cols.map { col =>
      unquoteAgressive(col)
    }
  }

  def unquoteAgressive(col: String): String = {
    val quotes = List("\"", "'", "`")
    var result = col.trim
    quotes.foreach { quote =>
      if (result.startsWith(quote) && result.endsWith(quote)) {
        result = result.substring(1, result.length - 1)
      }
    }
    result
  }


  def extractTableNames(sql: String): List[String] = {
    val select = jsqlParse(sql)
    val finder = new TablesNamesFinder()
    val tableList = Option(finder.getTables(select)).map(_.asScala).getOrElse(Nil)
    val unquoted = tableList.map { domainAndTableName =>
      // We remove quotes if any
      SQLUtils.unquoteAgressive(domainAndTableName.split("\\.").toList).mkString(".")
    }
    unquoted.toList.distinct
  }
  def extractColumnNames(sql: String): List[String] = {
    var result: List[String] = Nil

    def extractColumnsFromPlainSelect(plainSelect: PlainSelect): Unit = {
      val selectItems = Option(plainSelect.getSelectItems).map(_.asScala).getOrElse(Nil)
      result = selectItems.map { selectItem =>
        selectItem.getASTNode.jjtGetLastToken().image
      }.toList
    }

    val selectVisitorAdapter = new SelectVisitorAdapter[Any]() {
      override def visit[T](plainSelect: PlainSelect, context: T): Any = {
        extractColumnsFromPlainSelect(plainSelect)
      }

      override def visit[T](setOpList: SetOperationList, context: T): Any = {
        val plainSelect = setOpList.getSelect(0).getPlainSelect
        extractColumnsFromPlainSelect(plainSelect)
      }
    }
    val statementVisitor = new StatementVisitorAdapter[Any]() {
      override def visit[T](select: Select, context: T): Any = {
        select.accept(selectVisitorAdapter, null)
      }
    }
    val select = jsqlParse(sql)
    select.accept(statementVisitor, null)
    result
  }


}
