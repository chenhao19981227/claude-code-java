package com.claude.code.command;

public class LocalCommandResult {
    public enum ResultType { TEXT, COMPACT, SKIP }

    private final ResultType type;
    private final String value;

    private LocalCommandResult(ResultType type, String value) {
        this.type = type;
        this.value = value;
    }

    public ResultType getType() { return type; }
    public String getValue() { return value; }

    public static LocalCommandResult text(String value) { return new LocalCommandResult(ResultType.TEXT, value); }
    public static LocalCommandResult compact() { return new LocalCommandResult(ResultType.COMPACT, ""); }
    public static LocalCommandResult skip() { return new LocalCommandResult(ResultType.SKIP, null); }
}
