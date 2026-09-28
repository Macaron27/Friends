package net.md_5.bungee.api.chat;

public class ComponentBuilder {
    private final String text;
    public ComponentBuilder(String text) { this.text = text; }
    public BaseComponent[] create() { return new BaseComponent[] { new BaseComponent(text) }; }
}
