wit_bindgen::generate!({
    world: "webfunction",
    path: "wit",
});

use stardog::webfunction::types::{Accuracy, Binding, Literal};
use std::cell::RefCell;

// One instance per component invocation; state persists across aggregate-step
// calls until aggregate-finish is called.
thread_local! {
    static STATE: RefCell<i64> = const { RefCell::new(0) };
}

struct Component;

impl Guest for Component {
    fn evaluate(_args: Vec<Value>) -> Result<BindingSets, String> {
        Err("evaluate not supported by sum aggregator; use aggregate-step / aggregate-finish".into())
    }

    fn aggregate_step(args: Vec<Value>, mult: u64) -> Result<(), String> {
        let n = match args.first() {
            Some(Value::Literal(lit)) => lit
                .label
                .parse::<i64>()
                .map_err(|e| format!("value_0 not parseable as integer: {}", e))?,
            Some(_) => return Err("expected a literal argument".into()),
            None => return Err("expected at least one argument".into()),
        };
        let mult_i64: i64 = mult
            .try_into()
            .map_err(|_| "multiplicity exceeds i64::MAX".to_string())?;
        STATE.with(|s| *s.borrow_mut() += n.saturating_mul(mult_i64));
        Ok(())
    }

    fn aggregate_finish() -> Result<BindingSets, String> {
        let sum = STATE.with(|s| *s.borrow());
        // Reset for a subsequent aggregation if the same instance gets reused.
        STATE.with(|s| *s.borrow_mut() = 0);
        Ok(BindingSets {
            vars: vec!["value_0".into()],
            rows: vec![vec![Binding {
                name: "value_0".into(),
                value: Value::Literal(Literal {
                    label: sum.to_string(),
                    datatype: "http://www.w3.org/2001/XMLSchema#integer".into(),
                    lang: None,
                }),
            }]],
        })
    }

    fn cardinality_estimate(
        input: Cardinality,
        _args: Vec<Value>,
    ) -> Result<Cardinality, String> {
        // A sum of N rows produces exactly one row.
        Ok(Cardinality {
            value: 1.0f64.min(input.value),
            accuracy: Accuracy::Accurate,
        })
    }

    fn doc() -> BindingSets {
        BindingSets {
            vars: vec!["doc".into()],
            rows: vec![vec![Binding {
                name: "doc".into(),
                value: Value::Literal(Literal {
                    label: "Sums the integer value of value_0 across rows, weighted by row multiplicity.".into(),
                    datatype: "http://www.w3.org/2001/XMLSchema#string".into(),
                    lang: None,
                }),
            }]],
        }
    }
}

export!(Component);
