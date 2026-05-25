# Observability

This context defines the benchmark language used to describe repeatable performance scenarios and their documented comparison outputs.

## Language

**Benchmark Scenario**:
A named workload configuration used to compare observability runtime behavior under a specific sink or decorator pattern.
_Avoid_: One-off run, ad hoc test

**Comparative Result**:
A documented side-by-side benchmark outcome that helps readers compare scenarios, while explicitly remaining illustrative rather than a hard performance guarantee.
_Avoid_: Guarantee, SLA

## Example dialogue

Dev: "Is a benchmark scenario the same thing as a published result?"
Domain expert: "No. The scenario is the workload definition; the comparative result is the documented example output readers use to interpret the suite."
