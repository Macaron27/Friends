//! Styled chat text, independent of any server API: the subset of Adventure components Friends' messages use.
//! Children inherit their parent's style, as in Minecraft's own text components, so a tree built here renders the
//! same as the Java plugin's components. The Pumpkin glue converts it to the server's text components.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Color {
    Black,
    DarkBlue,
    DarkGreen,
    DarkAqua,
    DarkRed,
    DarkPurple,
    Gold,
    Gray,
    DarkGray,
    Blue,
    Green,
    Aqua,
    Red,
    LightPurple,
    Yellow,
    White,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Content {
    Plain(String),
    /// A rank prefix and name in legacy format with '&' codes (e.g. "&c[VIP] Bob"), parsed by the server.
    Legacy(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Click {
    RunCommand(String),
    SuggestCommand(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Text {
    pub content: Content,
    pub color: Option<Color>,
    pub bold: Option<bool>,
    pub italic: Option<bool>,
    pub strikethrough: Option<bool>,
    pub click: Option<Click>,
    pub hover: Option<Box<Text>>,
    pub children: Vec<Text>,
}

impl Text {
    pub fn plain(text: impl Into<String>) -> Text {
        Text {
            content: Content::Plain(text.into()),
            color: None,
            bold: None,
            italic: None,
            strikethrough: None,
            click: None,
            hover: None,
            children: Vec::new(),
        }
    }

    pub fn empty() -> Text {
        Text::plain("")
    }

    pub fn legacy(text: impl Into<String>) -> Text {
        Text { content: Content::Legacy(text.into()), ..Text::empty() }
    }

    pub fn color(mut self, color: Color) -> Text {
        self.color = Some(color);
        self
    }

    pub fn bold(mut self) -> Text {
        self.bold = Some(true);
        self
    }

    pub fn italic(mut self) -> Text {
        self.italic = Some(true);
        self
    }

    pub fn strikethrough(mut self) -> Text {
        self.strikethrough = Some(true);
        self
    }

    pub fn run_command(mut self, command: impl Into<String>) -> Text {
        self.click = Some(Click::RunCommand(command.into()));
        self
    }

    pub fn suggest_command(mut self, command: impl Into<String>) -> Text {
        self.click = Some(Click::SuggestCommand(command.into()));
        self
    }

    pub fn hover(mut self, hover: Text) -> Text {
        self.hover = Some(Box::new(hover));
        self
    }

    pub fn child(mut self, child: Text) -> Text {
        self.children.push(child);
        self
    }

    pub fn children(mut self, children: impl IntoIterator<Item = Text>) -> Text {
        self.children.extend(children);
        self
    }

    /// The text a player reads, without styles or legacy codes (logs and tests).
    pub fn to_plain(&self) -> String {
        let mut out = String::new();
        self.append_plain(&mut out);
        out
    }

    fn append_plain(&self, out: &mut String) {
        match &self.content {
            Content::Plain(s) => out.push_str(s),
            Content::Legacy(s) => out.push_str(&strip_legacy(s)),
        }
        for child in &self.children {
            child.append_plain(out);
        }
    }
}

/// Removes '&' colour and format codes, including "&#RRGGBB".
fn strip_legacy(s: &str) -> String {
    let chars: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '&' {
            if chars.get(i + 1) == Some(&'#')
                && chars.len() >= i + 8
                && chars[i + 2..i + 8].iter().all(char::is_ascii_hexdigit)
            {
                i += 8;
                continue;
            }
            if chars.get(i + 1).is_some_and(|c| "0123456789abcdefklmnorABCDEFKLMNOR".contains(*c)) {
                i += 2;
                continue;
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}
