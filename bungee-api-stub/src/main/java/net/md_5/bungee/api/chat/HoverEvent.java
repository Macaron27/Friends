package net.md_5.bungee.api.chat;

public class HoverEvent {
    public enum Action { SHOW_TEXT }
    private final Action action;
    private final BaseComponent[] value;
    public HoverEvent(Action action, BaseComponent[] value) { this.action = action; this.value = value; }
    public Action getAction() { return action; }
    public BaseComponent[] getValue() { return value; }
}
