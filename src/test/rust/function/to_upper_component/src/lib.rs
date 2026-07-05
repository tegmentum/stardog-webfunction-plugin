wit_bindgen::generate!({
    world: "webfunction",
    path: "wit",
});

use stardog::webfunction::types::{Accuracy, Binding, Literal};

struct Component;

impl Guest for Component {
    fn evaluate(args: Vec<Value>) -> Result<BindingSets, String> {
        let upper = match args.first() {
            Some(Value::Literal(lit)) => lit.label.to_uppercase(),
            Some(_) => return Err("expected a literal argument".into()),
            None => return Err("expected at least one argument".into()),
        };
        Ok(BindingSets {
            vars: vec!["value_0".into()],
            rows: vec![vec![Binding {
                name: "value_0".into(),
                value: Value::Literal(Literal {
                    label: upper,
                    datatype: "http://www.w3.org/2001/XMLSchema#string".into(),
                    lang: None,
                }),
            }]],
        })
    }

    fn aggregate_step(_args: Vec<Value>, _mult: u64) -> Result<(), String> {
        Err("aggregate-step not implemented by to_upper_component".into())
    }

    fn aggregate_finish() -> Result<BindingSets, String> {
        Err("aggregate-finish not implemented by to_upper_component".into())
    }

    fn cardinality_estimate(input: Cardinality, _args: Vec<Value>) -> Result<Cardinality, String> {
        Ok(Cardinality {
            value: input.value,
            accuracy: Accuracy::Accurate,
        })
    }

    fn doc() -> BindingSets {
        BindingSets {
            vars: vec!["doc".into()],
            rows: vec![vec![Binding {
                name: "doc".into(),
                value: Value::Literal(Literal {
                    label: "Uppercases a string literal.".into(),
                    datatype: "http://www.w3.org/2001/XMLSchema#string".into(),
                    lang: None,
                }),
            }]],
        }
    }
}

export!(Component);
