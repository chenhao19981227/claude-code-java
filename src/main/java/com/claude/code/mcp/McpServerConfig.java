package com.claude.code.mcp;

import java.util.Map;

public class McpServerConfig {
    private String name;
    private String command;
    private String[] args;
    private String transport;
    private Map<String, String> env;

    public McpServerConfig(String name, String command, String[] args) {
        this.name = name;
        this.command = command;
        this.args = args != null ? args : new String[0];
        this.transport = "stdio";
    }

    public String getName() { return name; }
    public String getCommand() { return command; }
    public String[] getArgs() { return args; }
    public String getTransport() { return transport; }
    public Map<String, String> getEnv() { return env; }

    public void setEnv(Map<String, String> env) { this.env = env; }
}
