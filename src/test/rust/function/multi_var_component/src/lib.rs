wit_bindgen::generate!({
    world: "webfunction",
    path: "wit",
});

use stardog::webfunction::types::{Accuracy, Binding, Literal};

struct Component;

const XSD_STRING: &str = "http://www.w3.org/2001/XMLSchema#string";
const XSD_INTEGER: &str = "http://www.w3.org/2001/XMLSchema#integer";

fn string_literal(label: &str) -> Value {
    Value::Literal(Literal {
        label: label.into(),
        datatype: XSD_STRING.into(),
        lang: None,
    })
}

fn integer_literal(n: i64) -> Value {
    Value::Literal(Literal {
        label: n.to_string(),
        datatype: XSD_INTEGER.into(),
        lang: None,
    })
}

fn row(label: &str, upper: &str, length: i64) -> Vec<Binding> {
    vec![
        Binding {
            name: "label".into(),
            value: string_literal(label),
        },
        Binding {
            name: "upper".into(),
            value: string_literal(upper),
        },
        Binding {
            name: "length".into(),
            value: integer_literal(length),
        },
    ]
}

impl Guest for Component {
    fn evaluate(_args: Vec<Value>) -> Result<BindingSets, String> {
        Ok(BindingSets {
            vars: vec!["label".into(), "upper".into(), "length".into()],
            rows: vec![
                row("stardog", "STARDOG", 7),
                row("jena", "JENA", 4),
            ],
        })
    }

    fn aggregate_step(_args: Vec<Value>, _mult: u64) -> Result<(), String> {
        Err("aggregate-step not implemented by multi_var_component".into())
    }

    fn aggregate_finish() -> Result<BindingSets, String> {
        Err("aggregate-finish not implemented by multi_var_component".into())
    }

    fn cardinality_estimate(input: Cardinality, _args: Vec<Value>) -> Result<Cardinality, String> {
        Ok(Cardinality {
            value: input.value.max(2.0),
            accuracy: Accuracy::Accurate,
        })
    }

    fn doc() -> BindingSets {
        BindingSets {
            vars: vec!["doc".into()],
            rows: vec![vec![Binding {
                name: "doc".into(),
                value: string_literal(
                    "Multi-variable, multi-row demo component. Returns two rows over \
                     vars (label, upper, length) to exercise the WIT binding-sets shape.",
                ),
            }]],
        }
    }
}

export!(Component);
