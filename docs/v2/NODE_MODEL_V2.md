# Node Model V2

## Core Definition

Node is a Knowledge Item, not only a Question.

A Node represents a piece of information participating in requirement exploration.

```
Node
 ├── identity
 ├── metadata
 ├── content
 ├── lifecycle
 ├── provenance
 └── relations
```

## Initial Node Types

### IDEA
User thoughts or observations.

### QUESTION
An exploration question created by user or Agent.

### REQUIREMENT
Confirmed or proposed product need.

### DECISION
A chosen direction or constraint.

### RESOURCE
External knowledge source.
Examples: file, image, URL, repository, API documentation.

### RISK
Potential problem requiring attention.

### SUMMARY
A generated state summary.

## Extension Rule

Do not create specialized nodes for every resource.

Prefer:

```
RESOURCE
  subtype: FILE | IMAGE | CODE | URL
```

New capabilities should extend content handlers, not create tightly coupled business objects.

## Lifecycle

```
DRAFT
  |
ACTIVE
  |
 +------------+
 |            |
 v            v
CONFIRMED   CHALLENGED
              |
              v
        SUPERSEDED

LOCKED
```

History is preserved; changes create new states instead of rewriting the past.
