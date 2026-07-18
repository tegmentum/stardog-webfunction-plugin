//! Reference filter component for the Stardog webfunction plugin's
//! component-mode dispatch tests. Exports one function, `upper`, on the
//! base `tegmentum:webfunction/extension` interface.
//!
//! Migrated from the pre-Arc-4 flat `evaluate` export (single free
//! function per component) to the shared `sparql-extension` world
//! (multi-function extension + `call(name, args)` dispatch), so it can
//! ride the Java-side `WitCallableResource`-based dispatch that the
//! Stardog host now uses.
//!
//! The other two interfaces (`aggregate`, `property-function`) that
//! the shared world requires are stubbed: `register-*` returns `[]` and
//! the dispatch functions error defensively. This crate provides only
//! the filter.

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

impl ExtensionGuest for Component {
    fn register() -> Vec<FunctionDescriptor> {
        vec![FunctionDescriptor {
            name: "upper".to_string(),
            min_arity: 1,
            max_arity: Some(1),
        }]
    }

    fn call(name: String, args: Vec<WitTerm>) -> Result<WitTerm, String> {
        match name.as_str() {
            "upper" => upper(&args),
            other => Err(format!("to_upper_component: unknown function '{other}'")),
        }
    }
}

/// Aggregate interface stub — this component provides no aggregates.
impl AggregateGuest for Component {
    type AggregateState = UnreachableState;

    fn register_aggregates() -> Vec<AggregateDescriptor> {
        Vec::new()
    }

    fn new_aggregate(name: String) -> Result<AggregateState, String> {
        Err(format!(
            "to_upper_component: unknown aggregate '{name}' (this component provides none)"
        ))
    }
}

pub struct UnreachableState;

impl GuestAggregateState for UnreachableState {
    fn step(&self, _args: Vec<WitTerm>) -> Result<(), String> {
        Err("to_upper_component: aggregate state was never constructed".into())
    }

    fn finish(&self) -> Result<WitTerm, String> {
        Err("to_upper_component: aggregate state was never constructed".into())
    }
}

/// Property-function interface stub — this component provides none.
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
            "to_upper_component: unknown property function '{name}' (this component provides none)"
        ))
    }
}

fn upper(args: &[WitTerm]) -> Result<WitTerm, String> {
    let [arg] = args else {
        return Err(format!(
            "upper: expected 1 argument, got {}",
            args.len()
        ));
    };
    let literal = match arg {
        WitTerm::Literal(l) => l,
        WitTerm::NamedNode(_) => return Err("upper: argument must be a literal, got IRI".into()),
        WitTerm::BlankNode(_) => {
            return Err("upper: argument must be a literal, got blank node".into())
        }
        WitTerm::Triple(_) => {
            return Err("upper: argument must be a literal, got quoted triple".into())
        }
    };
    Ok(WitTerm::Literal(WitLiteral {
        value: literal.value.to_uppercase(),
        datatype: literal.datatype.clone(),
        language: literal.language.clone(),
    }))
}

bindings::export!(Component with_types_in bindings);
