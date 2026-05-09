# 🎲 Lucky Dice — Provably-Fair Blockchain Game

A **serverless** dice betting game on the **Credits blockchain**. No server needed — the smart contract is fully trustless using a cryptographic commit-reveal pattern with `getSeed()` as the source of randomness.

Players pick a number 1–6, place a CS bet, and the contract rolls a verifiably fair dice. Match the number → win **5× your bet**!

---

## 📁 Files

| File | Purpose |
|------|---------|
| `DiceGame.java` | Credits blockchain smart contract (Java) |
| `index.html` | Self-contained dApp — WalletConnect + game UI |

---

## 🔐 How It Works (Provably Fair)

```
Player generates:  playerSeed = random 32-byte hex
Player computes:   playerSeedHash = sha256(playerSeed)

Step 1: createBet(number, amount, playerSeedHash)
        → CS locked in contract
        → contract captures getSeed() (blockchain entropy)

Step 2: reveal(betId, playerSeed, nonce)
        → Contract verifies sha256(playerSeed) == playerSeedHash
        → Computes: dice = sha256(playerSeed + contractSeed + nonce) % 6 + 1
        → Auto-pays winner

Neither side can cheat:
• Player can't predict contractSeed at bet time
• Contract can't cheat because player's seed is committed before contract seed is used
• Player must reveal their original seed (cannot change after commitment)
```



MIT — build, modify, and deploy freely.
