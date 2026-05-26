package com.example.upi.service;

import com.example.upi.dto.Dtos.*;
import com.example.upi.exception.UpiException;
import com.example.upi.model.Account;
import com.example.upi.model.Transaction.TxnChannel;
import com.example.upi.model.UssdSession;
import com.example.upi.model.UssdSession.SessionState;
import com.example.upi.repository.UssdSessionRepository;
import com.example.upi.util.Hashing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * USSD state machine. *99# style menu:
 *   1 - Send money (online via gateway)
 *   2 - Check balance
 *   3 - UPI Lite balance
 *   4 - Top up UPI Lite
 *   5 - Exit
 *
 * Each USSD request is stateless from the carrier's POV — they just forward
 * the user's keypress with the same sessionId. We rebuild conversational state
 * from the persisted session record.
 *
 * Idempotency: each session generates an idempotency key on first dial. If the
 * carrier retries (network blip), our backend doesn't double-process.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UssdService {

    private final UssdSessionRepository sessionRepo;
    private final AccountService accountService;
    private final TransactionService transactionService;

    @Transactional
    public UssdResponse handle(UssdRequest req) {
        UssdSession session = sessionRepo.findById(req.getSessionId()).orElse(null);

        if (session == null) {
            return showMainMenu(req);
        }
        if (session.isExpired()) {
            sessionRepo.delete(session);
            return end("Session expired. Please dial *99# again.");
        }

        String input = req.getText() == null ? "" : req.getText().trim();

        return switch (session.getState()) {
            case MENU -> handleMenuChoice(session, input);
            case AWAITING_VPA -> handleVpa(session, input);
            case AWAITING_AMOUNT -> handleAmount(session, input);
            case AWAITING_REMARK -> handleRemark(session, input);
            case AWAITING_PIN -> handlePinForTransfer(session, input);
            case AWAITING_LITE_LOAD_AMOUNT -> handleLiteLoadAmount(session, input);
            case AWAITING_LITE_LOAD_PIN -> handleLiteLoadPin(session, input);
        };
    }

    // ===== State handlers =====

    private UssdResponse showMainMenu(UssdRequest req) {
        try {
            accountService.getByMobile(req.getMobileNumber());
        } catch (UpiException e) {
            return end("Mobile not registered. Please register first.");
        }
        UssdSession s = UssdSession.builder()
                .sessionId(req.getSessionId())
                .mobileNumber(req.getMobileNumber())
                .state(SessionState.MENU)
                .idempotencyKey("USSD-" + UUID.randomUUID())
                .build();
        sessionRepo.save(s);
        return cont("""
                Welcome to *99# UPI
                1. Send Money
                2. Check Balance
                3. UPI Lite Balance
                4. Top Up UPI Lite
                5. Exit""");
    }

    private UssdResponse handleMenuChoice(UssdSession s, String input) {
        return switch (input) {
            case "1" -> { advance(s, SessionState.AWAITING_VPA); yield cont("Enter receiver VPA:"); }
            case "2" -> {
                Account acc = accountService.getByMobile(s.getMobileNumber());
                sessionRepo.delete(s);
                yield end("Balance: ₹" + acc.getBalance() + "\nLite: ₹" + acc.getUpiLiteBalance());
            }
            case "3" -> {
                Account acc = accountService.getByMobile(s.getMobileNumber());
                sessionRepo.delete(s);
                yield end("UPI Lite balance: ₹" + acc.getUpiLiteBalance() +
                          "\nLimit: ₹" + acc.getUpiLiteMaxBalance());
            }
            case "4" -> { advance(s, SessionState.AWAITING_LITE_LOAD_AMOUNT); yield cont("Enter amount to load to UPI Lite:"); }
            case "5" -> { sessionRepo.delete(s); yield end("Thank you."); }
            default -> cont("Invalid choice. 1-5:");
        };
    }

    private UssdResponse handleVpa(UssdSession s, String input) {
        try { accountService.getByVpa(input); }
        catch (UpiException e) { sessionRepo.delete(s); return end("VPA not found: " + input); }
        s.setReceiverVpa(input);
        advance(s, SessionState.AWAITING_AMOUNT);
        return cont("Enter amount (max ₹500 offline):");
    }

    private UssdResponse handleAmount(UssdSession s, String input) {
        BigDecimal amt;
        try { amt = new BigDecimal(input); if (amt.signum() <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException e) { sessionRepo.delete(s); return end("Invalid amount."); }
        s.setAmount(amt);
        advance(s, SessionState.AWAITING_REMARK);
        return cont("Enter remarks (or 0 to skip):");
    }

    private UssdResponse handleRemark(UssdSession s, String input) {
        if (!"0".equals(input)) s.setRemarks(input);
        advance(s, SessionState.AWAITING_PIN);
        return cont("Enter UPI PIN to confirm ₹" + s.getAmount() + " to " + s.getReceiverVpa() + ":");
    }

    private UssdResponse handlePinForTransfer(UssdSession s, String pin) {
        Account sender = accountService.getByMobile(s.getMobileNumber());
        TransferRequest tr = TransferRequest.builder()
                .idempotencyKey(s.getIdempotencyKey())
                .senderVpa(sender.getVpa())
                .receiverVpa(s.getReceiverVpa())
                .amount(s.getAmount())
                .upiPin(pin)
                .remarks(s.getRemarks())
                .build();
        try {
            var resp = transactionService.transfer(tr, TxnChannel.USSD_OFFLINE);
            sessionRepo.delete(s);
            return end("SUCCESS! ₹" + s.getAmount() + " sent to " + s.getReceiverVpa() +
                       "\nUTR: " + resp.getUtr() + "\nBalance: ₹" + resp.getNewBalance());
        } catch (UpiException e) {
            sessionRepo.delete(s);
            return end("FAILED: " + e.getMessage());
        }
    }

    private UssdResponse handleLiteLoadAmount(UssdSession s, String input) {
        BigDecimal amt;
        try { amt = new BigDecimal(input); if (amt.signum() <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException e) { sessionRepo.delete(s); return end("Invalid amount."); }
        s.setAmount(amt);
        advance(s, SessionState.AWAITING_LITE_LOAD_PIN);
        return cont("Enter UPI PIN to load ₹" + amt + " to UPI Lite:");
    }

    private UssdResponse handleLiteLoadPin(UssdSession s, String pin) {
        Account acc = accountService.getByMobile(s.getMobileNumber());
        // Light pre-check; full verification happens in service
        if (!Hashing.matches(pin, acc.getPinHash())) {
            sessionRepo.delete(s);
            return end("Invalid PIN.");
        }
        LiteLoadRequest req = LiteLoadRequest.builder()
                .idempotencyKey(s.getIdempotencyKey())
                .amount(s.getAmount()).upiPin(pin).build();
        try {
            var resp = transactionService.loadUpiLite(acc.getVpa(), req);
            sessionRepo.delete(s);
            return end("Loaded ₹" + s.getAmount() + " to UPI Lite\nNew Lite: ₹" + resp.getNewBalance());
        } catch (UpiException e) {
            sessionRepo.delete(s);
            return end("FAILED: " + e.getMessage());
        }
    }

    private void advance(UssdSession s, SessionState next) {
        s.setState(next);
        sessionRepo.save(s);
    }

    private UssdResponse cont(String msg) { return new UssdResponse("CON", msg); }
    private UssdResponse end(String msg)  { return new UssdResponse("END", msg); }
}
