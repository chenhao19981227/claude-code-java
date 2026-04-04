package com.claude.code.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandRegistry {
    private final List<Command> commands = new ArrayList<>();

    public void register(Command command) { commands.add(command); }

    public Command findByName(String name) {
        if (name == null) return null;
        for (Command cmd : commands) {
            if (cmd.getName().equalsIgnoreCase(name) && cmd.isEnabled()) return cmd;
        }
        return null;
    }

    public List<Command> getAllCommands() { return Collections.unmodifiableList(commands); }

    public List<Command> getEnabledCommands() {
        List<Command> result = new ArrayList<>();
        for (Command cmd : commands) {
            if (cmd.isEnabled()) result.add(cmd);
        }
        return result;
    }

    public List<String> getCommandNames() {
        List<String> names = new ArrayList<>();
        for (Command cmd : commands) {
            if (cmd.isEnabled() && cmd.isUserInvocable()) names.add("/" + cmd.getName());
        }
        Collections.sort(names);
        return names;
    }
}
