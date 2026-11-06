//! Core text to Pumpkin text components, and player lookups.

use friends_core::text::{Click, Color, Content, Text};
use pumpkin_plugin_api::common::NamedColor;
use pumpkin_plugin_api::text::TextComponent;

fn named(color: Color) -> NamedColor {
    match color {
        Color::Black => NamedColor::Black,
        Color::DarkBlue => NamedColor::DarkBlue,
        Color::DarkGreen => NamedColor::DarkGreen,
        Color::DarkAqua => NamedColor::DarkAqua,
        Color::DarkRed => NamedColor::DarkRed,
        Color::DarkPurple => NamedColor::DarkPurple,
        Color::Gold => NamedColor::Gold,
        Color::Gray => NamedColor::Gray,
        Color::DarkGray => NamedColor::DarkGray,
        Color::Blue => NamedColor::Blue,
        Color::Green => NamedColor::Green,
        Color::Aqua => NamedColor::Aqua,
        Color::Red => NamedColor::Red,
        Color::LightPurple => NamedColor::LightPurple,
        Color::Yellow => NamedColor::Yellow,
        Color::White => NamedColor::White,
    }
}

pub fn component(text: &Text) -> TextComponent {
    let mut c = match &text.content {
        Content::Plain(s) => TextComponent::text(s),
        Content::Legacy(s) => TextComponent::from_legacy_string_with_code(s, '&'),
    };
    if let Some(color) = text.color {
        c = c.color_named(named(color));
    }
    if let Some(bold) = text.bold {
        c = c.bold(bold);
    }
    if let Some(italic) = text.italic {
        c = c.italic(italic);
    }
    if let Some(strikethrough) = text.strikethrough {
        c = c.strikethrough(strikethrough);
    }
    c = match &text.click {
        Some(Click::RunCommand(command)) => c.click_run_command(command),
        Some(Click::SuggestCommand(command)) => c.click_suggest_command(command),
        None => c,
    };
    if let Some(hover) = &text.hover {
        c = c.hover_show_text(component(hover));
    }
    for child in &text.children {
        c = c.add_child(component(child));
    }
    c
}

pub fn uuid(id: pumpkin_plugin_api::uuid::Uuid) -> uuid::Uuid {
    uuid::Uuid::from_u64_pair(id.high, id.low)
}

pub fn wit_uuid(id: uuid::Uuid) -> pumpkin_plugin_api::uuid::Uuid {
    let (high, low) = id.as_u64_pair();
    pumpkin_plugin_api::uuid::Uuid { high, low }
}
