# Investment AI Advisor contract v1

Windows v0.12 and Android v0.32 use this same privacy boundary.

Only percentage-based portfolio information may be sent to an AI provider: current
allocation %, target %, selected performance %, prior recommendation outcome %, public
market/category identifiers, and an optional compact Atlas market brief.

Never send portfolio value, price, quantity, balance, Toman/Rial/USD amounts,
bank/account identifiers, raw transactions, credentials/tokens, personal identity,
email/phone/address, or user-entered private display names.

Request format: `investment.ai.snapshot`, schema 1.

The next phase adds a structured recommendation response. Target changes must remain
suggestions until the user explicitly accepts them. Recommendation outcomes are stored
locally; the model prompt does not rewrite itself.

Atlas is an optional context producer only. Android must not depend on Telegram or the
Atlas internal queue. Stale/unavailable Atlas context must not block portfolio use.
