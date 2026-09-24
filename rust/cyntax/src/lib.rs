#![feature(iter_chain)]
#![feature(iter_intersperse)]
use std::{collections::HashMap, fmt::Write};

use cyntax_common::{
    ast::{PreprocessingToken, Whitespace},
    ctx::{HasContext, ParseContext},
    spanned::Spanned,
};
use cyntax_errors::codespan_reporting::files::SimpleFiles;
use expand::{Expander, PResult};
use prepend::PrependingPeekableIterator;
use tree::{IntoTokenTree, TokenTree};

use crate::{expand::MacroDefinition, macros::MacroParameterList};

pub mod expand;
mod macros;
mod prepend;
mod substitute;
mod tree;
pub fn preprocess_str(input: &str, macros: &[(String, MacroD)]) -> String {
    let mut pc = ParseContext {
        files: SimpleFiles::new(),
        current_file: 0,
    };
    pc.files.add("input".to_string(), input.to_string());

    let lexer = cyntax_lexer::lexer::Lexer::new(&mut pc, input);
    let tokens = lexer.collect::<Vec<_>>();
    let tt = Preprocessor::new(&mut pc, &tokens).token_trees.into_iter();
    let macro_map = {
        let mut map: HashMap<String, MacroDefinition> = HashMap::new();
        for mac in macros {
            let processed = match &mac.1 {
                MacroD::Simple(s) => process_macro(s),
                MacroD::Complex(p, s) => process_complex_macro(p, s),
            };

            map.insert(mac.0.clone(), processed);
        }
        map
    };
    let mut expander = Expander::new(&mut pc, PrependingPeekableIterator::new(tt), macro_map);
    expander.expand().unwrap();

    let mut out = String::new();
    {
        write_tokens(input, expander.output.iter(), &mut out);
    }

    out
}
pub struct Preprocessor<'src> {
    // macros and whatever
    ctx: &'src mut ParseContext,
    token_trees: Vec<TokenTree>,
}
impl<'src> HasContext for Preprocessor<'src> {
    fn ctx(&self) -> &ParseContext {
        self.ctx
    }
}
impl<'src> Preprocessor<'src> {
    pub fn new(
        ctx: &'src mut ParseContext,
        tokens: &'src [Spanned<PreprocessingToken>],
    ) -> Preprocessor<'src> {
        let itt = IntoTokenTree {
            ctx,
            tokens: tokens.iter().peekable(),
            expecting_opposition: false,
        }
        .collect::<Vec<_>>();

        Self {
            ctx,
            token_trees: itt,
        }
    }
}

pub enum MacroD<'a> {
    Simple(&'a str),
    Complex(Vec<&'a str>, &'a str),
}
pub fn process_macro(input: &str) -> MacroDefinition {
    let mut pc = ParseContext {
        files: SimpleFiles::new(),
        current_file: 0,
    };
    let lexer = cyntax_lexer::lexer::Lexer::new(&mut pc, input);
    let tokens = lexer.collect::<Vec<_>>();
    MacroDefinition::Object(tokens)
}
pub fn process_complex_macro(params: &[&str], input: &str) -> MacroDefinition {
    let mut pc = ParseContext {
        files: SimpleFiles::new(),
        current_file: 0,
    };
    let lexer = cyntax_lexer::lexer::Lexer::new(&mut pc, input);
    let tokens = lexer.collect::<Vec<_>>();
    MacroDefinition::Function {
        parameter_list: MacroParameterList {
            parameters: params.iter().map(|s| s.to_string()).collect::<Vec<_>>(),
            variadic: false,
        },
        replacement_list: tokens,
    }
}

pub fn write_tokens<'src, W: Write, I: Iterator<Item = &'src Spanned<PreprocessingToken>>>(
    source: &'src str,
    tokens: I,
    w: &mut W,
) {
    for spanned_token in tokens {
        match &spanned_token.value {
            PreprocessingToken::Identifier(identifier) => {
                write!(w, "{}", identifier).unwrap();
            }
            PreprocessingToken::BlueIdentifier(identifier) => {
                write!(w, "{}", identifier).unwrap();
            }
            PreprocessingToken::StringLiteral(string) => {
                write!(w, "\"{}\"", string).unwrap();
            }
            PreprocessingToken::CharLiteral(chars) => {
                write!(w, "'{}'", chars).unwrap();
            }
            PreprocessingToken::PPNumber(number) => {
                write!(w, "{}", number).unwrap();
            }
            PreprocessingToken::Delimited(d) => {
                write_tokens(source, std::iter::once(&d.opener), w);
                write_tokens(source, d.inner_tokens.iter(), w);
                write_tokens(source, std::iter::once(&d.closer), w);
            }
            PreprocessingToken::ControlLine(inner) => {
                write!(w, "#").unwrap();
                write_tokens(source, inner.iter(), w);
            }
            PreprocessingToken::Whitespace(whitespace) => match whitespace {
                Whitespace::Space => write!(w, " ").unwrap(),
                Whitespace::Newline => write!(w, "\n").unwrap(),
                Whitespace::Tab => write!(w, "\t").unwrap(),
            },
            PreprocessingToken::Punctuator(punctuator) => {
                write!(w, "{}", punctuator.to_string()).unwrap();
            }
        }
    }
}
