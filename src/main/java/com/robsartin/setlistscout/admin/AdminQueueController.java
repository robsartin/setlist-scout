package com.robsartin.setlistscout.admin;

import com.robsartin.setlistscout.shared.AdminGuard;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin-only queues page (#201): what the background job machinery is doing right now --
 * failed work with its error, counts by queue/status, due-now backlog, oldest {@code
 * next_due_at}, and import-queue state per owner. There is otherwise no way to see this short of
 * querying the production database directly.
 * <p>
 * Read-only: no retry/cancel/re-due controls, no auto-refresh or polling (this app ships no
 * custom JavaScript -- a manual reload is fine for an operational page). {@link
 * AdminGuard#require()} is the actual access control; {@code fragments/layout.html}'s nav link
 * (gated on {@code isAdmin}, from {@code review.NavModelAdvice}) is a UI convenience only, same
 * as every other admin-only affordance in this app.
 */
@Controller
public class AdminQueueController {

    private final AdminGuard adminGuard;
    private final AdminQueueService adminQueueService;

    public AdminQueueController(AdminGuard adminGuard, AdminQueueService adminQueueService) {
        this.adminGuard = adminGuard;
        this.adminQueueService = adminQueueService;
    }

    @GetMapping("/admin/queues")
    public String queues(Model model) {
        adminGuard.require();
        model.addAttribute("snapshot", adminQueueService.snapshot());
        return "admin-queues";
    }
}
