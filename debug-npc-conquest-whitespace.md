# Debug Session: npc-conquest-whitespace

Status: [OPEN]

## Symptom

Clicking “征服” on an NPC in the map causes the frontend to become a blank white screen.

## Hypotheses

1. The conquest API returns an error that the frontend does not handle.
2. NPC data lacks a field required by the conquest UI, causing a JavaScript exception.
3. The conquest succeeds but the subsequent map/game-state refresh receives an unexpected shape.
4. The backend conquest operation throws an exception and the frontend loses its rendered state.
5. The click handler triggers an unintended page reload or clears application state.

## Evidence

The user supplied the runtime stack trace:

- `ReferenceError: isScout is not defined`
- `Object.renderDispatch (world.js:631:9)`
- The error was surfaced during `ws-handlers.js` refresh handling.

Static inspection confirmed `renderDispatch` used `isScout` when rendering the dispatch button, but did not define it. This directly confirms hypothesis 2 (frontend render exception) and rejects the API/backend hypotheses as the cause of the white screen.

## Fix

Added `var isScout = dt.action === 'scout';` in `renderDispatch` before the variable is used.

## Verification

- `node --check frontend/js/world.js` passed.
- The fix preserves the intended button text: scout actions show “派出侦查机”; conquest/plunder actions show “出发!”.
- Awaiting user confirmation after refreshing the page and retrying NPC conquest.
