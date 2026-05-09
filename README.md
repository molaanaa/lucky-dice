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

---

## 🚀 Deploy the dApp to GitHub Pages

### Step 1: Create a GitHub Repository

1. Go to [github.com/new](https://github.com/new)
2. Repository name: `lucky-dice`
3. Choose **Public**
4. Click **Create repository**

### Step 2: Push Your Files

```bash
cd /Users/molaanaa/Projects/mydapp/dice-game

# Initialize git
git init
git add index.html
git commit -m "Lucky Dice dApp — serverless provably-fair game"

# Connect to your repo (replace YOUR_USERNAME)
git remote add origin https://github.com/YOUR_USERNAME/lucky-dice.git
git branch -M main
git push -u origin main
```

### Step 3: Enable GitHub Pages

1. Go to your repo on GitHub → **Settings** (top tab)
2. Click **Pages** (left sidebar, under "Code and automation")
3. Under "Build and deployment":
   - **Source**: `Deploy from a branch`
   - **Branch**: `main` → `/ (root)` → **Save**
4. Wait 1–2 minutes. Your site is live at:
   ```
   https://YOUR_USERNAME.github.io/lucky-dice/
   ```

### Step 4: Connect Custom Domain `game.molaanaa.com`

**On GitHub (repo Settings → Pages):**
1. Under "Custom domain", enter: `game.molaanaa.com`
2. Click **Save**
3. Wait for "DNS check in progress" to become "DNS check successful"
4. Check ✅ **Enforce HTTPS** (may take a few minutes to appear)

**On Cloudflare (dns.cloudflare.com → your domain):**
1. Go to **DNS** → **Records**
2. Add record:

| Type  | Name   | Target                      | Proxy Status |
|-------|--------|-----------------------------|--------------|
| CNAME | `game` | `YOUR_USERNAME.github.io`   | ☁️ Proxied   |

3. Wait 5–10 minutes for DNS to propagate
4. Visit: `https://game.molaanaa.com`

---

## 🚀 Alternative: Deploy to Cloudflare Pages

```bash
# Install Wrangler
npm install -g wrangler

# Login to Cloudflare
wrangler login

# Deploy
cd /Users/molaanaa/Projects/mydapp/dice-game
npx wrangler pages deploy . --project-name=lucky-dice

# Output: ✨ https://lucky-dice.pages.dev

# Then in Cloudflare Dashboard → Workers & Pages → lucky-dice → Custom Domains:
# Add: game.molaanaa.com
```

---

## 📝 Smart Contract Deployment (Credits Blockchain)

1. Compile `DiceGame.java` using the Credits SDK compiler
2. Deploy the contract to Credits mainnet/testnet
3. Copy the deployed **contract address**

### Configure the dApp

Open `index.html` and replace **two occurrences** of `CONTRACT_ADDRESS_HERE` with your deployed contract address (search for "CONTRACT_ADDRESS_HERE").

```javascript
receiver: "CONTRACT_ADDRESS_HERE",  // ← Replace with your contract address
```

### Optional: Get Your Own WalletConnect Project ID

The dApp uses a shared WalletConnect project ID. For production, get your own:

1. Go to [cloud.reown.com](https://cloud.reown.com)
2. Create a new project
3. Copy your Project ID
4. Replace `PROJECT_ID` in `index.html` with yours

---

## 🎮 Playing the Game

1. Open `https://game.molaanaa.com` on your phone
2. Tap **Connect** → scan QR code with BABA Wallet
3. **Pick a number** (1–6) from the dice grid
4. Enter your **bet amount** (or use quick bet buttons)
5. Tap **🎲 ROLL THE DICE!**
6. Wallet will ask you to sign 2 transactions (Step 1: lock bet, Step 2: reveal seed)
7. Dice animates → result overlay shows win/loss

Keyboard shortcuts: Press `1`–`6` to pick number, `Enter`/`Space` to roll.

---

## ⚙️ Admin Contract Functions

Once deployed, call these from your wallet (contract owner):

| Function | Description |
|----------|-------------|
| `setWinMultiplier("5")` | Change win multiplier (2–10) |
| `setHouseFeePercent("2")` | Set house fee percentage (0–10) |
| `withdrawFees("100")` | Withdraw accumulated CS from losing bets |

---

## 📄 License

MIT — build, modify, and deploy freely.