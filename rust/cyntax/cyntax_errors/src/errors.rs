use cyntax_common::{
    ast::PreprocessingToken,
    spanned::{Location, Spanned},
};

use crate::{Diagnostic, DiagnosticSeverity, Label};
#[derive(Debug)]

pub struct UnmatchedDelimiter {
    pub opening_delimiter_location: Location,
    pub potential_closing_delimiter_location: Location,
    pub closing_delimiter: PreprocessingToken,
}
impl Diagnostic for UnmatchedDelimiter {
    fn title<'a>(&self) -> &'a str {
        "Unmatched delimiter"
    }
    fn severity(&self) -> DiagnosticSeverity {
        DiagnosticSeverity::Error
    }
    fn labels(&self) -> Vec<crate::Label> {
        vec![
            Label {
                kind: crate::LabelKind::Primary,
                location: self.opening_delimiter_location.clone(),
                message: "Unmatched delimiter".to_string(),
            },
            Label {
                kind: crate::LabelKind::Secondary,
                location: self.potential_closing_delimiter_location.clone(),
                message: "Potential location for a closing delimiter".to_string(),
            },
        ]
    }
}
#[derive(Debug)]
pub struct UnterminatedTreeNode {
    pub opening_token: Location,
}
impl Diagnostic for UnterminatedTreeNode {
    fn title<'a>(&self) -> &'a str {
        "Unterminated tree node"
    }
    fn severity(&self) -> DiagnosticSeverity {
        DiagnosticSeverity::Error
    }
    fn labels(&self) -> Vec<crate::Label> {
        vec![Label {
            kind: crate::LabelKind::Primary,
            location: self.opening_token.clone(),
            message: "No closer for this group".to_string(),
        }]
    }
}

pub struct UnknownDirective(pub Location);
impl Diagnostic for UnknownDirective {
    fn title<'a>(&self) -> &'a str {
        "Unknown directive"
    }

    fn severity(&self) -> DiagnosticSeverity {
        DiagnosticSeverity::Error
    }
    fn labels(&self) -> Vec<Label> {
        vec![Label {
            kind: crate::LabelKind::Primary,
            location: self.0.clone(),
            message: "".to_string(),
        }]
    }
}

pub struct DanglingEndif(pub Location);
impl Diagnostic for DanglingEndif {
    fn title<'a>(&self) -> &'a str {
        "Dangling end if"
    }

    fn severity(&self) -> DiagnosticSeverity {
        DiagnosticSeverity::Error
    }
    fn labels(&self) -> Vec<Label> {
        vec![Label {
            kind: crate::LabelKind::Primary,
            location: self.0.clone(),
            message: "This endif directive has no matching opening directive".to_string(),
        }]
    }
}
pub struct ExpectedButFound {
    pub location: Location,
    pub expected: String,
    pub found: String,
}
impl Diagnostic for ExpectedButFound {
    fn title<'a>(&self) -> &'a str {
        "Encountered wrong token"
    }

    fn severity(&self) -> DiagnosticSeverity {
        DiagnosticSeverity::Error
    }
    fn labels(&self) -> Vec<Label> {
        vec![
            Label {
                kind: crate::LabelKind::Primary,
                location: self.location.clone(),
                message: self.expected.clone(),
            },
            Label {
                kind: crate::LabelKind::Secondary,
                location: self.location.clone(),
                message: self.found.clone(),
            },
        ]
    }
}

pub struct ErrorDirective(pub Location, pub Option<Spanned<PreprocessingToken>>);

impl Diagnostic for ErrorDirective {
    fn title<'a>(&self) -> &'a str {
        "Encountered error directive"
    }

    fn severity(&self) -> DiagnosticSeverity {
        DiagnosticSeverity::Error
    }
    fn labels(&self) -> Vec<Label> {
        let msg = match &self.1 {
            Some(msg) => Some(Label {
                kind: crate::LabelKind::Secondary,
                location: msg.location.clone(),
                message: "Error message provided".into(),
            }),
            None => None,
        };
        match msg {
            Some(msg_label) => {
                vec![
                    Label {
                        kind: crate::LabelKind::Primary,
                        location: self.0.clone(),
                        message: "".into(),
                    },
                    msg_label,
                ]
            }
            None => {
                vec![Label {
                    kind: crate::LabelKind::Primary,
                    location: self.0.clone(),
                    message: "".into(),
                }]
            }
        }
    }
}

pub struct SimpleError(pub Location, pub String);

impl Diagnostic for SimpleError {
    fn title<'a>(&self) -> &'a str {
        "simple error"
    }

    fn severity(&self) -> DiagnosticSeverity {
        DiagnosticSeverity::Error
    }
    fn labels(&self) -> Vec<Label> {
        vec![Label {
            kind: crate::LabelKind::Primary,
            location: self.0.clone(),
            message: self.1.clone(),
        }]
    }
}
pub struct SimpleWarning(pub Location, pub String);

impl Diagnostic for SimpleWarning {
    fn title<'a>(&self) -> &'a str {
        "simple warning"
    }

    fn severity(&self) -> DiagnosticSeverity {
        DiagnosticSeverity::Warning
    }
    fn labels(&self) -> Vec<Label> {
        vec![Label {
            kind: crate::LabelKind::Primary,
            location: self.0.clone(),
            message: self.1.clone(),
        }]
    }
}
