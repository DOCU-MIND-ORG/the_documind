Classify this query across two dimensions:

INTENT:
- GREETING_ACK: social, no information need (e.g. "hi", "thanks", "ok")
- META_HISTORY: asking about the conversation itself (e.g. "what did I say", "our chat")
- DOCUMENT_QA: asking about document content (e.g. "summarize this", "what is the policy")
- CLARIFICATION: asking to elaborate on the last response (e.g. "explain that", "what do you mean")
- BOT_QA: asking about the bot itself, its capabilities, or who it is (e.g. "what are your capabilities", "who are you")

SCOPE:
- NONE: no retrieval needed (e.g. greetings, bot capabilities, asking to repeat the exact previous answer). NEVER use NONE if the user is asking for factual information or a new entity (e.g. "what about X?").
- SPECIFIC_DOC: answer is in the file just uploaded
- CORPUS: answer requires searching all indexed documents

CONTEXT TO EVALUATE:
User Query: "{question}"
Recent Context (if any): "{history}"

SYSTEM PREDICTION (HEURISTIC HINT):
Our rule-based system predicts that this query falls under:
Intent: {provisionalIntent}
Scope: {provisionalScope}

INSTRUCTION:
Review the user query and the system prediction.
SYSTEM HINT (may be incorrect — use as a starting point only): If the hint conflicts with the semantic meaning of the query, override it. Your classification takes precedence.

Return exactly and only the JSON object.
