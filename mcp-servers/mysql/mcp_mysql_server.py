"""
MySQL MCP Server - JSON-RPC 2.0 over stdio

Provides database query, schema inspection, and table management tools
for Claude Code via the Model Context Protocol.

Usage:
    python mcp_mysql_server.py

Configuration (config.yaml):
    mcpServers:
      - name: mysql
        command: python
        args: ["mcp-servers/mysql/mcp_mysql_server.py"]
        env:
          DB_HOST: localhost
          DB_PORT: 3306
          DB_USER: root
          DB_PASSWORD: your_password
          DB_NAME: your_database
"""

import sys
import json
import os
import signal


def get_config():
    """Read connection config from environment variables."""
    return {
        "host": os.environ.get("DB_HOST", "localhost"),
        "port": int(os.environ.get("DB_PORT", "3306")),
        "user": os.environ.get("DB_USER", "root"),
        "password": os.environ.get("DB_PASSWORD", ""),
        "database": os.environ.get("DB_NAME", ""),
    }


def get_connection(config):
    """Create a database connection. Tries mysql.connector, then pymysql."""
    try:
        import mysql.connector
        conn = mysql.connector.connect(
            host=config["host"],
            port=config["port"],
            user=config["user"],
            password=config["password"],
            database=config["database"] or None,
            autocommit=True,
            connect_timeout=10,
        )
        return conn
    except ImportError:
        pass

    try:
        import pymysql
        conn = pymysql.connect(
            host=config["host"],
            port=config["port"],
            user=config["user"],
            password=config["password"],
            database=config["database"] or None,
            autocommit=True,
            connect_timeout=10,
        )
        return conn
    except ImportError:
        pass

    raise RuntimeError(
        "No MySQL driver found. Install one: pip install mysql-connector-python  OR  pip install pymysql"
    )


def tool_query(sql, config):
    """Execute a SELECT query and return results."""
    if not sql.strip().upper().startswith("SELECT"):
        return [{"type": "text", "text": "Error: Only SELECT queries are allowed for safety. Use execute_update for INSERT/UPDATE/DELETE."}]

    try:
        conn = get_connection(config)
        try:
            cursor = conn.cursor()
            cursor.execute(sql)
            columns = [desc[0] for desc in cursor.description] if cursor.description else []
            rows = cursor.fetchall()

            # Format as table
            if not rows:
                return [{"type": "text", "text": "Query returned 0 rows."}]

            col_widths = [len(str(c)) for c in columns]
            for row in rows:
                for i, val in enumerate(row):
                    col_widths[i] = max(col_widths[i], len(str(val) if val is not None else "NULL"))

            header = " | ".join(str(c).ljust(w) for c, w in zip(columns, col_widths))
            separator = "-+-".join("-" * w for w in col_widths)
            lines = [header, separator]
            for row in rows:
                lines.append(" | ".join(str(v if v is not None else "NULL").ljust(w) for v, w in zip(row, col_widths)))

            result = "\n".join(lines)
            result += "\n\n(%d rows)" % len(rows)

            # Truncate very large results
            if len(result) > 50000:
                result = result[:50000] + "\n\n... (truncated, %d total chars)" % len(result)

            return [{"type": "text", "text": result}]
        finally:
            conn.close()
    except Exception as e:
        return [{"type": "text", "text": "Error: %s" % str(e)}]


def tool_execute_update(sql, config):
    """Execute INSERT/UPDATE/DELETE/DDL statements. Returns affected rows."""
    if sql.strip().upper().startswith("SELECT"):
        return [{"type": "text", "text": "Error: Use query for SELECT statements."}]

    try:
        conn = get_connection(config)
        try:
            cursor = conn.cursor()
            cursor.execute(sql)
            affected = cursor.rowcount
            conn.commit()
            return [{"type": "text", "text": "OK. %d row(s) affected." % affected}]
        finally:
            conn.close()
    except Exception as e:
        return [{"type": "text", "text": "Error: %s" % str(e)}]


def tool_list_tables(config):
    """List all tables in the current database."""
    if not config["database"]:
        return [{"type": "text", "text": "No database selected. Set DB_NAME in config."}]

    try:
        conn = get_connection(config)
        try:
            cursor = conn.cursor()
            cursor.execute("SHOW TABLES")
            tables = [row[0] for row in cursor.fetchall()]
            if not tables:
                return [{"type": "text", "text": "No tables found in database '%s'." % config["database"]}]

            # Get row counts for each table
            lines = ["Tables in '%s':\n" % config["database"]]
            for table in tables:
                cursor.execute("SELECT COUNT(*) FROM `%s`" % table)
                count = cursor.fetchone()[0]
                lines.append("  %s  (%d rows)" % (table, count))

            return [{"type": "text", "text": "\n".join(lines)}]
        finally:
            conn.close()
    except Exception as e:
        return [{"type": "text", "text": "Error: %s" % str(e)}]


def tool_describe_table(table, config):
    """Show the schema (columns, types, keys) of a table."""
    if not table or not table.strip():
        return [{"type": "text", "text": "Error: table name is required."}]

    table = table.strip().strip("`")

    try:
        conn = get_connection(config)
        try:
            cursor = conn.cursor()

            # Column info
            cursor.execute("DESCRIBE `%s`" % table)
            columns = cursor.fetchall()

            # Build formatted output
            lines = ["Table: %s\n" % table]
            header = "%-30s %-20s %-8s %-10s %s" % ("Field", "Type", "Null", "Key", "Default")
            lines.append(header)
            lines.append("-" * len(header))
            for col in columns:
                field = col[0]
                type_str = col[1]
                null = col[2]
                key = col[3] if col[3] else ""
                default = str(col[4]) if col[4] is not None else "NULL"
                lines.append("%-30s %-20s %-8s %-10s %s" % (field, type_str, null, key, default))

            # Show CREATE TABLE for indexes
            cursor.execute("SHOW CREATE TABLE `%s`" % table)
            create_result = cursor.fetchone()
            if create_result and len(create_result) > 1:
                lines.append("\n-- DDL --")
                lines.append(create_result[1])

            return [{"type": "text", "text": "\n".join(lines)}]
        finally:
            conn.close()
    except Exception as e:
        return [{"type": "text", "text": "Error: %s" % str(e)}]


def tool_list_databases(config):
    """List all available databases."""
    try:
        conn = get_connection(config)
        try:
            cursor = conn.cursor()
            cursor.execute("SHOW DATABASES")
            dbs = [row[0] for row in cursor.fetchall()]
            current = config["database"] or "(none)"
            lines = ["Databases (current: %s):\n" % current]
            for db in dbs:
                marker = " * " if db == config["database"] else "   "
                lines.append("%s%s" % (marker, db))
            return [{"type": "text", "text": "\n".join(lines)}]
        finally:
            conn.close()
    except Exception as e:
        return [{"type": "text", "text": "Error: %s" % str(e)}]


# Tool definitions (MCP format)
TOOLS = [
    {
        "name": "query",
        "description": "Execute a SELECT query on the MySQL database. Returns formatted results. Only SELECT is allowed for safety.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "sql": {
                    "type": "string",
                    "description": "SQL SELECT statement to execute"
                }
            },
            "required": ["sql"]
        }
    },
    {
        "name": "execute_update",
        "description": "Execute INSERT, UPDATE, DELETE, or DDL statements. Returns affected row count. Use with caution.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "sql": {
                    "type": "string",
                    "description": "SQL statement (INSERT/UPDATE/DELETE/DDL)"
                }
            },
            "required": ["sql"]
        }
    },
    {
        "name": "list_tables",
        "description": "List all tables in the current database with row counts.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "describe_table",
        "description": "Show the schema (columns, types, keys, DDL) of a specific table.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "table": {
                    "type": "string",
                    "description": "Table name"
                }
            },
            "required": ["table"]
        }
    },
    {
        "name": "list_databases",
        "description": "List all available databases on the MySQL server.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    }
]


TOOL_HANDLERS = {
    "query": lambda args, config: tool_query(args.get("sql", ""), config),
    "execute_update": lambda args, config: tool_execute_update(args.get("sql", ""), config),
    "list_tables": lambda args, config: tool_list_tables(config),
    "describe_table": lambda args, config: tool_describe_table(args.get("table", ""), config),
    "list_databases": lambda args, config: tool_list_databases(config),
}


def handle_initialize(params, request_id):
    """Handle MCP initialize request."""
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "result": {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {
                "name": "mysql-mcp-server",
                "version": "1.0.0"
            }
        }
    }


def handle_tools_list(params, request_id):
    """Handle tools/list request."""
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "result": {
            "tools": TOOLS
        }
    }


def handle_tools_call(params, request_id, config):
    """Handle tools/call request."""
    tool_name = params.get("name", "")
    arguments = params.get("arguments", {})

    handler = TOOL_HANDLERS.get(tool_name)
    if not handler:
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "content": [{"type": "text", "text": "Unknown tool: %s" % tool_name}],
                "isError": True
            }
        }

    try:
        content = handler(arguments, config)
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "content": content,
                "isError": False
            }
        }
    except Exception as e:
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "content": [{"type": "text", "text": "Internal error: %s" % str(e)}],
                "isError": True
            }
        }


def main():
    config = get_config()

    # Log config to stderr (won't interfere with stdio JSON)
    sys.stderr.write("[mysql-mcp] Starting...\n")
    sys.stderr.write("[mysql-mcp] Host: %s:%d, User: %s, Database: %s\n" % (
        config["host"], config["port"], config["user"], config["database"] or "(not set)"
    ))
    sys.stderr.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            msg = json.loads(line)
        except json.JSONDecodeError as e:
            sys.stderr.write("[mysql-mcp] Invalid JSON: %s\n" % e)
            sys.stderr.flush()
            continue

        request_id = msg.get("id")
        method = msg.get("method", "")
        params = msg.get("params", {})

        if method == "initialize":
            response = handle_initialize(params, request_id)
        elif method == "tools/list":
            response = handle_tools_list(params, request_id)
        elif method == "tools/call":
            response = handle_tools_call(params, request_id, config)
        elif method == "ping":
            response = {"jsonrpc": "2.0", "id": request_id, "result": {}}
        else:
            # Unknown method - still respond to avoid hanging
            response = {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32601, "message": "Method not found: %s" % method}
            }

        sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
