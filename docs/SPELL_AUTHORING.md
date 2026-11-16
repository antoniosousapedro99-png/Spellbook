# Writing a Spell

A spell is a single capability with a declared contract. The Spell Engine
handles permissions, validation, timeouts, recording and events — you write
only the handler body.

## Minimal spell

```python
# cybernomicon/spells/trace.py
from ..spell_engine.base import ExecutionContext
from ..spell_engine import spell

@spell(
    id="trace",
    name="Trace",
    version="1.0.0",
    description="Analyze reachability to an authorized host.",
    permissions=("network.discover",),
    input_schema={
        "type": "object",
        "properties": {"address": {"type": "string"}},
        "required": ["address"],
        "additionalProperties": False,
    },
    output_schema={
        "type": "object",
        "properties": {"reachable": {"type": "boolean"}},
        "required": ["reachable"],
    },
    timeout_seconds=30.0,
)
async def trace(params: dict, ctx: ExecutionContext) -> dict:
    # 1. Authorize every target you will touch. Raises AuthorizationError
    #    (engine -> status "denied") if the address is out of scope.
    ctx.authorization.assert_authorized(params["address"])

    # 2. Report progress for the live activity feed.
    ctx.progress(f"tracing {params['address']}")

    # 3. Do the work (unprivileged, no shelling out to arbitrary commands).
    reachable = await _check(params["address"])

    # 4. Anything observed from the network is untrusted — record it so it is
    #    fenced before an LLM ever sees it.
    #    ctx.observe(banner, source=f"banner:{addr}:{port}")

    return {"reachable": reachable}
```

Then register it for loading in `cybernomicon/spells/__init__.py`:

```python
from . import scry, identify, archive, trace  # noqa: F401
```

## Rules

- **Authorize first.** Any spell that touches the network must call
  `ctx.authorization.assert_authorized(addr)` for every address, before the
  packet. Never read the allowlist yourself — use the injected object.
- **Declare a tight schema.** `additionalProperties: False` on inputs; validate
  outputs too, so a buggy spell can't poison the Chronicle.
- **Treat network data as data.** Route banners/hostnames through
  `ctx.observe(...)`; never interpolate them into a command or an LLM prompt
  unfenced.
- **Stay unprivileged and bounded.** No raw sockets, no arbitrary subprocess,
  no credential attacks. Respect your `timeout_seconds`.
- **Declare permissions.** Daemons only run spells whose permissions they hold.

## Testing

Build an isolated registry so you don't touch the global one:

```python
from cybernomicon.spell_engine import SpellRegistry, SpellEngine
reg = SpellRegistry()
# @spell(..., into=reg) on your handler, then:
engine = SpellEngine(reg, db, events)
res = await engine.execute("trace", {"address": "127.0.0.1"})
```
