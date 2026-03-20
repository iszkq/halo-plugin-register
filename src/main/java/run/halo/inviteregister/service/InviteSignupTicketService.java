package run.halo.inviteregister.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class InviteSignupTicketService {

    public static final String COOKIE_NAME = "halo_invite_signup_ticket";
    private static final Duration TICKET_TTL = Duration.ofMinutes(15);

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public String create(String inviteName) {
        cleanupExpired();
        String token = UUID.randomUUID().toString();
        tickets.put(token, new Ticket(inviteName, Instant.now().plus(TICKET_TTL)));
        return token;
    }

    public String getInviteName(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Ticket ticket = tickets.get(token);
        if (ticket == null) {
            return null;
        }
        if (ticket.expiresAt().isBefore(Instant.now())) {
            tickets.remove(token);
            return null;
        }
        return ticket.inviteName();
    }

    public void invalidate(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tickets.remove(token);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record Ticket(String inviteName, Instant expiresAt) {
    }
}
