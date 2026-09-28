package net.md_5.bungee.api.chat;

public class ClickEvent {
    public enum Action { RUN_COMMAND }
    private final Action action;
    private final String value;
    public ClickEvent(Action action, String value) { this.action = action; this.value = value; }
    public Action getAction() { return action; }
    public String getValue() { return value; }
}
