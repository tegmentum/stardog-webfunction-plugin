wit_bindgen::generate!({
    world: "webfunction",
    path: "wit",
});

use stardog::webfunction_test::types::{Accuracy, Binding, Literal};

struct Component;

const XSD_STRING: &str = "http://www.w3.org/2001/XMLSchema#string";

impl Guest for Component {
    fn evaluate(args: Vec<Term>) -> Result<BindingSets, String> {
        let upper = match args.first() {
            Some(Term::Literal(lit)) => lit.value.to_uppercase(),
            Some(Term::NamedNode(_)) => return Err("expected a literal argument, got IRI".into()),
            Some(Term::BlankNode(_)) => {
                return Err("expected a literal argument, got blank node".into())
            }
            Some(Term::Triple(_)) => {
                return Err("expected a literal argument, got quoted triple".into())
            }
            None => return Err("expected at least one argument".into()),
        };
        Ok(BindingSets {
            vars: vec!["value_0".into()],
            rows: vec![vec![Binding {
                variable: "value_0".into(),
                value: Term::Literal(Literal {
                    value: upper,
                    datatype: Some(XSD_STRING.into()),
                    language: None,
                }),
            }]],
        })
    }

    fn aggregate_step(_args: Vec<Term>, _mult: u64) -> Result<(), String> {
        Err("aggregate-step not implemented by to_upper_component".into())
    }

    fn aggregate_finish() -> Result<BindingSets, String> {
        Err("aggregate-finish not implemented by to_upper_component".into())
    }

    fn cardinality_estimate(input: Cardinality, _args: Vec<Term>) -> Result<Cardinality, String> {
        Ok(Cardinality {
            value: input.value,
            accuracy: Accuracy::Accurate,
        })
    }

    fn doc() -> BindingSets {
        BindingSets {
            vars: vec!["doc".into()],
            rows: vec![vec![Binding {
                variable: "doc".into(),
                value: Term::Literal(Literal {
                    value: "Uppercases a string literal.".into(),
                    datatype: Some(XSD_STRING.into()),
                    language: None,
                }),
            }]],
        }
    }
}

export!(Component);
