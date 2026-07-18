//! Multi-argument filter component for the Stardog webfunction plugin's
//! component-mode dispatch tests. Exports one function, `describe`, on
//! the base `tegmentum:webfunction/extension` interface that takes two
//! string-literal arguments and returns a single concatenated literal.
//!
//! The pre-migration crate returned multi-row multi-var binding-sets
//! from a flat `evaluate` export. The base `sparql-extension` filter
//! interface returns a single `term`; the multi-row test surface moved
//! to the property-function interface (this component provides none).
//! `describe(label, upper)` collapses the old two-arg row shape to a
//! single string result — enough to exercise the multi-argument call
//! path (`args.len() == 2`) end-to-end through
//! `WitCallableResource`-style dispatch.

#[allow(warnings)]
mod bindings;

use bindings::exports::tegmentum::webfunction::aggregate::{
    AggregateDescriptor, AggregateState, Guest as AggregateGuest, GuestAggregateState,
};
use bindings::exports::tegmentum::webfunction::extension::{
    FunctionDescriptor, Guest as ExtensionGuest,
};
use bindings::exports::tegmentum::webfunction::property_function::{
    BindingRow, Guest as PropertyFunctionGuest, PropertyDescriptor,
};
use bindings::tegmentum::webfunction::types::{Literal as WitLiteral, Term as WitTerm};

struct Component;

const XSD_STRING: &str = "http://www.w3.org/2001/XMLSchema#string";

impl ExtensionGuest for Component {
    fn register() -> Vec<FunctionDescriptor> {
        vec![FunctionDescriptor {
            name: "describe".to_string(),
            min_arity: 2,
            max_arity: Some(2),
        }]
    }

    fn call(name: String, args: Vec<WitTerm>) -> Result<WitTerm, String> {
        match name.as_str() {
            "describe" => describe(&args),
            other => Err(format!(
                "multi_var_component: unknown function '{other}'"
            )),
        }
    }
}

/// Aggregate interface stub.
impl AggregateGuest for Component {
    type AggregateState = UnreachableState;

    fn register_aggregates() -> Vec<AggregateDescriptor> {
        Vec::new()
    }

    fn new_aggregate(name: String) -> Result<AggregateState, String> {
        Err(format!(
            "multi_var_component: unknown aggregate '{name}' (this component provides none)"
        ))
    }
}

pub struct UnreachableState;

impl GuestAggregateState for UnreachableState {
    fn step(&self, _args: Vec<WitTerm>) -> Result<(), String> {
        Err("multi_var_component: aggregate state was never constructed".into())
    }

    fn finish(&self) -> Result<WitTerm, String> {
        Err("multi_var_component: aggregate state was never constructed".into())
    }
}

/// Property-function interface stub.
impl PropertyFunctionGuest for Component {
    fn register_property_functions() -> Vec<PropertyDescriptor> {
        Vec::new()
    }

    fn evaluate(
        name: String,
        _subjects: Vec<WitTerm>,
        _objects: Vec<WitTerm>,
    ) -> Result<Vec<BindingRow>, String> {
        Err(format!(
            "multi_var_component: unknown property function '{name}' (this component provides none)"
        ))
    }
}

fn describe(args: &[WitTerm]) -> Result<WitTerm, String> {
    let [label_term, upper_term] = args else {
        return Err(format!(
            "describe: expected 2 arguments, got {}",
            args.len()
        ));
    };
    let label = literal_value(label_term, "label")?;
    let upper = literal_value(upper_term, "upper")?;
    Ok(WitTerm::Literal(WitLiteral {
        value: format!("{label}={upper}"),
        datatype: Some(XSD_STRING.to_string()),
        language: None,
    }))
}

fn literal_value(term: &WitTerm, position: &str) -> Result<String, String> {
    match term {
        WitTerm::Literal(l) => Ok(l.value.clone()),
        WitTerm::NamedNode(_) => {
            Err(format!("describe: {position} must be a literal, got IRI"))
        }
        WitTerm::BlankNode(_) => Err(format!(
            "describe: {position} must be a literal, got blank node"
        )),
        WitTerm::Triple(_) => Err(format!(
            "describe: {position} must be a literal, got quoted triple"
        )),
    }
}

bindings::export!(Component with_types_in bindings);
