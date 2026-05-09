package com.molaanaa.dicegame;

import com.credits.scapi.annotations.Getter;
import com.credits.scapi.v2.SmartContract;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lucky Dice — provably-fair serverless dice game.
 *
 * No server needed. The player commits a seed hash, then reveals the seed.
 * The contract combines the player's seed with the blockchain's getSeed()
 * (captured at bet creation) to produce a verifiably random dice roll.
 *
 * Dice formula:
 *   roll = ( sha256( playerSeed + contractSeed + nonce ) % 6 ) + 1
 *
 *   playerSeed    = chosen by player, revealed in step 2
 *   contractSeed  = getSeed() captured when bet is created
 *
 * Flow (only 2 steps):
 *  1. Player: createBet(pickedNumber, betAmount, playerSeedHash) → locks CS, returns betId
 *  2. Player: reveal(betId, playerSeed) → computes dice, auto-payout
 *
 * Win multiplier: 5× (configurable 2–10×)
 */
public class DiceGame extends SmartContract {

    private static final String STATE_CREATED  = "CREATED";
    private static final String STATE_FINISHED = "FINISHED";

    private final String owner;
    private BigDecimal houseFeePercent = new BigDecimal("2");
    private long betIdCounter = 1;
    private int winMultiplier = 5;

    private static class Bet implements Serializable {
        String id;
        String player;
        int pickedNumber;
        BigDecimal amount;
        String state;

        // Player commitment
        String playerSeedHash;
        String playerSeed;

        // Contract seed captured at bet creation time (blockchain entropy)
        byte[] contractSeed;

        // Result
        int diceResult;
        boolean playerWon;
    }

    private final Map<String, Bet> bets = new LinkedHashMap<>();

    public DiceGame() {
        super();
        this.owner = initiator;
    }

    @Override
    public String payable(BigDecimal amount, byte[] userData) {
        return "Direct transfers not accepted. Call createBet to play.";
    }

    // ── Step 1: Create bet ───────────────────────────────────────
    /**
     * Player commits to a bet without revealing their secret seed.
     *
     * @param pickedNumberStr  1–6, the number the player is betting on
     * @param betAmount        CS amount to wager
     * @param seedHashHex      64-char hex: sha256(playerSecretSeed)
     * @return                 betId e.g. "DICE-1"
     */
    public String createBet(String pickedNumberStr, String betAmount, String seedHashHex) {
        int pickedNumber;
        try {
            pickedNumber = Integer.parseInt(pickedNumberStr);
        } catch (NumberFormatException e) {
            return "Error: pickedNumber must be an integer 1–6";
        }
        if (pickedNumber < 1 || pickedNumber > 6) {
            return "Error: number must be 1–6";
        }

        BigDecimal bet;
        try {
            bet = new BigDecimal(betAmount);
        } catch (NumberFormatException e) {
            return "Error: invalid bet amount";
        }
        if (bet.signum() <= 0) {
            return "Error: bet must be positive";
        }
        if (seedHashHex == null || seedHashHex.length() != 64) {
            return "Error: seed hash must be 64 hex characters (sha256 of your secret)";
        }

        // Lock the bet: transfer CS from player to contract
        sendTransaction(initiator, contractAddress, bet.doubleValue(),
                        ("bet-" + betIdCounter).getBytes(StandardCharsets.UTF_8));

        Bet b = new Bet();
        b.id = "DICE-" + betIdCounter;
        b.player = initiator;
        b.pickedNumber = pickedNumber;
        b.amount = bet;
        b.state = STATE_CREATED;
        b.playerSeedHash = seedHashHex;
        b.contractSeed = getSeed();  // capture blockchain entropy NOW
        bets.put(b.id, b);
        betIdCounter++;

        return "Bet created: " + b.id + " | You picked " + pickedNumber +
               " | Bet: " + bet.toPlainString() + " CS | Now call reveal with your secret seed.";
    }

    // ── Step 2: Reveal & resolve ─────────────────────────────────
    /**
     * Player reveals their secret seed. Contract verifies it matches the
     * committed hash, computes the dice using both player seed and captured
     * contract seed, and auto-pays if the player guessed correctly.
     *
     * @param betId         The bet ID from createBet
     * @param playerSeedHex The secret seed (any hex string you chose)
     * @param nonceStr      A unique number for this reveal (prevents replay)
     * @return              Result message with dice outcome
     */
    public String reveal(String betId, String playerSeedHex, String nonceStr) {
        Bet b = bets.get(betId);
        if (b == null) return "Error: bet not found";
        if (!b.state.equals(STATE_CREATED)) return "Error: bet already resolved";
        if (!initiator.equals(b.player)) return "Error: only the player can reveal";

        // Verify player seed matches the committed hash
        byte[] seedBytes = hexToBytes(playerSeedHex);
        String computedHash = bytesToHex(sha256(seedBytes));
        if (!computedHash.equals(b.playerSeedHash)) {
            return "Error: seed hash mismatch — wrong secret seed provided";
        }

        long nonce;
        try {
            nonce = Long.parseLong(nonceStr);
        } catch (NumberFormatException e) {
            return "Error: invalid nonce (must be a number)";
        }

        b.playerSeed = playerSeedHex;

        // ── Provably-fair dice computation ───────────────────
        int dice = computeDiceInternal(b.contractSeed, seedBytes, nonce);
        b.diceResult = dice;
        b.playerWon = (dice == b.pickedNumber);

        // Payout
        payout(b);
        b.state = STATE_FINISHED;

        String outcome = b.playerWon ? "🎉 YOU WIN!" : "😞 You lose.";
        BigDecimal winAmount = b.playerWon
            ? b.amount.multiply(BigDecimal.valueOf(winMultiplier))
            : BigDecimal.ZERO;

        return "Dice: " + dice + " | You picked: " + b.pickedNumber +
               " | " + outcome +
               (b.playerWon ? " | Won: " + winAmount.toPlainString() + " CS" : " | Lost: " + b.amount.toPlainString() + " CS");
    }

    // ── Internal dice computation ────────────────────────────────
    private int computeDiceInternal(byte[] contractSeed, byte[] playerSeedBytes, long nonce) {
        byte[] input = new byte[playerSeedBytes.length + contractSeed.length + 8];
        System.arraycopy(playerSeedBytes, 0, input, 0, playerSeedBytes.length);
        System.arraycopy(contractSeed, 0, input, playerSeedBytes.length, contractSeed.length);
        // nonce as big-endian long
        for (int i = 7; i >= 0; i--) {
            input[playerSeedBytes.length + contractSeed.length + (7 - i)] =
                (byte) ((nonce >>> (i * 8)) & 0xFF);
        }
        byte[] hash = sha256(input);
        return (hash[0] & 0xFF) % 6 + 1;
    }

    /**
     * Public getter for clients to independently verify dice.
     */
    @Getter
    public int computeDice(String betId, String playerSeedHex, String nonceStr) {
        Bet b = bets.get(betId);
        if (b == null || b.contractSeed == null) return 0;
        long nonce;
        try { nonce = Long.parseLong(nonceStr); }
        catch (NumberFormatException e) { return 0; }
        return computeDiceInternal(b.contractSeed, hexToBytes(playerSeedHex), nonce);
    }

    // ── Payout ──────────────────────────────────────────────────
    private void payout(Bet b) {
        if (b.playerWon) {
            BigDecimal winnings = b.amount.multiply(BigDecimal.valueOf(winMultiplier));
            sendTransaction(contractAddress, b.player, winnings.doubleValue(),
                            ("win-" + b.id).getBytes(StandardCharsets.UTF_8));
        }
        // If player loses, bet stays in contract (house earns)
    }

    // ── Admin methods ────────────────────────────────────────────
    public String setWinMultiplier(String multiplierStr) {
        if (!initiator.equals(owner)) return "Error: owner only";
        int m;
        try { m = Integer.parseInt(multiplierStr); }
        catch (NumberFormatException e) { return "Error: invalid multiplier"; }
        if (m < 2 || m > 10) return "Error: multiplier must be 2–10";
        winMultiplier = m;
        return "Win multiplier set to " + m + "×";
    }

    public String setHouseFeePercent(String percent) {
        if (!initiator.equals(owner)) return "Error: owner only";
        BigDecimal p;
        try { p = new BigDecimal(percent); }
        catch (NumberFormatException e) { return "Error: invalid number"; }
        if (p.compareTo(BigDecimal.ZERO) < 0 || p.compareTo(new BigDecimal("10")) > 0) {
            return "Error: fee must be 0–10%";
        }
        houseFeePercent = p;
        return "House fee set to " + percent + "%";
    }

    public String withdrawFees(String amount) {
        if (!initiator.equals(owner)) return "Error: owner only";
        BigDecimal amt;
        try { amt = new BigDecimal(amount); }
        catch (NumberFormatException e) { return "Error: invalid amount"; }
        if (amt.signum() <= 0) return "Error: amount must be positive";
        sendTransaction(contractAddress, owner, amt.doubleValue(),
                        "fee-withdraw".getBytes(StandardCharsets.UTF_8));
        return "Withdrawn " + amt.toPlainString() + " CS.";
    }

    // ── Getters ─────────────────────────────────────────────────
    @Getter public String getOwner()           { return owner; }
    @Getter public String getHouseFeePercent() { return houseFeePercent.toPlainString(); }
    @Getter public int    getWinMultiplier()   { return winMultiplier; }
    @Getter public long   getBetCounter()      { return betIdCounter - 1; }

    @Getter
    public String getBet(String betId) {
        Bet b = bets.get(betId);
        if (b == null) return "{}";
        StringBuilder sb = new StringBuilder(400);
        sb.append("{\"id\":\"").append(b.id).append("\"");
        sb.append(",\"player\":\"").append(b.player).append("\"");
        sb.append(",\"pickedNumber\":").append(b.pickedNumber);
        sb.append(",\"amount\":\"").append(b.amount.toPlainString()).append("\"");
        sb.append(",\"state\":\"").append(b.state).append("\"");
        sb.append(",\"playerSeedHash\":\"").append(nullToEmpty(b.playerSeedHash)).append("\"");
        sb.append(",\"diceResult\":").append(b.diceResult);
        sb.append(",\"playerWon\":").append(b.playerWon);
        sb.append(",\"contractSeed\":\"").append(
            b.contractSeed != null ? bytesToHex(b.contractSeed) : "").append("\"");
        sb.append('}');
        return sb.toString();
    }

    @Getter
    public String getContractSeedForBet(String betId) {
        Bet b = bets.get(betId);
        if (b == null || b.contractSeed == null) return "";
        return bytesToHex(b.contractSeed);
    }

    // ── SHA‑256 (FIPS 180‑4, same as backgammon) ───────────────
    private static final int[] SHA_K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private static byte[] sha256(byte[] msg) {
        int origLen = msg.length;
        int padLen = (56 - ((origLen + 1) % 64) + 64) % 64;
        byte[] padded = new byte[origLen + 1 + padLen + 8];
        System.arraycopy(msg, 0, padded, 0, origLen);
        padded[origLen] = (byte) 0x80;
        long bitLen = (long) origLen * 8L;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) (bitLen >>> (i * 8));
        }
        int h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
        int h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
        int[] W = new int[64];
        for (int chunk = 0; chunk < padded.length; chunk += 64) {
            for (int i = 0; i < 16; i++) {
                int j = chunk + i * 4;
                W[i] = ((padded[j] & 0xff) << 24) |
                       ((padded[j + 1] & 0xff) << 16) |
                       ((padded[j + 2] & 0xff) << 8) |
                        (padded[j + 3] & 0xff);
            }
            for (int i = 16; i < 64; i++) {
                int s0 = Integer.rotateRight(W[i - 15], 7) ^
                         Integer.rotateRight(W[i - 15], 18) ^ (W[i - 15] >>> 3);
                int s1 = Integer.rotateRight(W[i - 2], 17) ^
                         Integer.rotateRight(W[i - 2], 19) ^ (W[i - 2] >>> 10);
                W[i] = W[i - 16] + s0 + W[i - 7] + s1;
            }
            int a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
            for (int i = 0; i < 64; i++) {
                int S1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^
                         Integer.rotateRight(e, 25);
                int ch = (e & f) ^ (~e & g);
                int t1 = h + S1 + ch + SHA_K[i] + W[i];
                int S0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^
                         Integer.rotateRight(a, 22);
                int mj = (a & b) ^ (a & c) ^ (b & c);
                int t2 = S0 + mj;
                h = g; g = f; f = e; e = d + t1;
                d = c; c = b; b = a; a = t1 + t2;
            }
            h0 += a; h1 += b; h2 += c; h3 += d;
            h4 += e; h5 += f; h6 += g; h7 += h;
        }
        byte[] out = new byte[32];
        int[] H = { h0, h1, h2, h3, h4, h5, h6, h7 };
        for (int i = 0; i < 8; i++) {
            out[i * 4]     = (byte) (H[i] >>> 24);
            out[i * 4 + 1] = (byte) (H[i] >>> 16);
            out[i * 4 + 2] = (byte) (H[i] >>> 8);
            out[i * 4 + 3] = (byte)  H[i];
        }
        return out;
    }

    // ── Hex utilities ──────────────────────────────────────────
    private static byte[] hexToBytes(String hex) {
        String s = (hex.startsWith("0x") || hex.startsWith("0X")) ? hex.substring(2) : hex;
        if (s.length() % 2 != 0) throw new RuntimeException("invalid hex length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new RuntimeException("invalid hex char");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String bytesToHex(byte[] in) {
        StringBuilder sb = new StringBuilder(in.length * 2);
        for (byte b : in) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}