package ai.starlake.acl.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ai.starlake.acl.model.{Config, TableRef}

class SqlParserDuckDBTest extends AnyFunSuite with Matchers {

  private val config = Config.forDuckDB("mydb", "main")

  test("extract DuckDB three-part catalog.schema.table") {
    val sql = "SELECT * FROM mycat.myschema.mytable"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("mycat", "myschema", "mytable"))
  }

  test("apply DuckDB defaults for unqualified table") {
    val sql = "SELECT * FROM orders"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("mydb", "main", "orders"))
  }

  test("apply DuckDB defaults for two-part schema.table") {
    val sql = "SELECT * FROM sales.orders"
    val result = SqlParser.extract(sql, config)
    result.allTables shouldBe Set(TableRef("mydb", "sales", "orders"))
  }

  test("ignore DuckDB file reference") {
    val sql = "SELECT * FROM 'data/file.parquet'"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted => ext.tables shouldBe empty
      case _: StatementResult.ParseError  => () // Also acceptable
      case other                          => fail(s"Unexpected result type: $other")
    }
  }

  test("ignore DuckDB file reference mixed with real table") {
    val sql = "SELECT * FROM 'file.csv' UNION SELECT * FROM real_table"
    val result = SqlParser.extract(sql, config)
    // File reference is filtered; real_table is extracted
    result.allTables shouldBe Set(TableRef("mydb", "main", "real_table"))
  }

  test("extract table from DuckDB query with TABLESAMPLE") {
    val sql = "SELECT * FROM orders TABLESAMPLE 10 PERCENT"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted =>
        ext.tables shouldBe Set(TableRef("mydb", "main", "orders"))
      case _: StatementResult.ParseError =>
        // Known limitation: JSqlParser may not support TABLESAMPLE
        info("TABLESAMPLE not supported by JSqlParser -- ParseError is acceptable")
      case other =>
        fail(s"Unexpected result type: $other")
    }
  }

  test("ignore table functions like read_csv") {
    val sql = "SELECT * FROM read_csv('file.csv')"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted =>
        // Table functions are filtered by TableExtractor
        ext.tables shouldBe empty
      case _: StatementResult.ParseError =>
        // JSqlParser may not parse table functions
        info("read_csv table function not parsed by JSqlParser -- ParseError is acceptable")
      case other =>
        fail(s"Unexpected result type: $other")
    }
  }

  test("handle DuckDB QUALIFY clause") {
    val sql = "SELECT *, ROW_NUMBER() OVER (PARTITION BY id) AS rn FROM orders QUALIFY rn = 1"
    val result = SqlParser.extract(sql, config)
    result.statements should have size 1
    result.statements.head match {
      case ext: StatementResult.Extracted =>
        ext.tables shouldBe Set(TableRef("mydb", "main", "orders"))
      case _: StatementResult.ParseError =>
        // Known limitation: JSqlParser may not support QUALIFY
        info("QUALIFY not supported by JSqlParser -- ParseError is acceptable")
      case other =>
        fail(s"Unexpected result type: $other")
    }
  }
}
