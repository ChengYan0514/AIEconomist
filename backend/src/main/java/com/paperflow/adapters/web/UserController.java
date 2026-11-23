package com.paperflow.adapters.web;

import com.paperflow.account.UserManagement;
import com.paperflow.account.model.PlatformUser;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserManagement users;

    public UserController(UserManagement users) {
        this.users = users;
    }

    record UserView(String username, String displayName, String role, PlatformUser.Status status, OffsetDateTime createdAt) {
        static UserView from(PlatformUser user) {
            return new UserView(user.username(), user.displayName(), user.role(),
                    user.enabled() ? PlatformUser.Status.ACTIVE : PlatformUser.Status.DISABLED, user.createdAt());
        }
    }
    record CreateUser(String username, String displayName, String role) {}
    record UpdateUser(String displayName, String role) {}
    record SetUserStatus(PlatformUser.Status status) {}
    record IssuedPassword(UserView user, String temporaryPassword) {}

    @GetMapping
    Page<UserView> list(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
                        @RequestParam(required = false) PlatformUser.Status status,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = users.list(q, role, status, Page.offset(page, size), size);
        return new Page<>(result.items().stream().map(UserView::from).toList(), page, size, result.total());
    }

    @PostMapping
    ResponseEntity<IssuedPassword> create(@RequestBody CreateUser request, Principal actor) {
        var created = users.create(actor.getName(), request.username(), request.displayName(), request.role());
        return passwordResponse(HttpStatus.CREATED, created);
    }

    @PatchMapping("/{username}")
    UserView update(@PathVariable String username, @RequestBody UpdateUser request, Principal actor) {
        return UserView.from(users.update(actor.getName(), username, request.displayName(), request.role()));
    }

    @PutMapping("/{username}/status")
    UserView setStatus(@PathVariable String username, @RequestBody SetUserStatus request, Principal actor) {
        return UserView.from(users.setStatus(actor.getName(), username, request.status()));
    }

    @PostMapping("/{username}/reset-password")
    ResponseEntity<IssuedPassword> resetPassword(@PathVariable String username, Principal actor) {
        return passwordResponse(HttpStatus.OK, users.resetPassword(actor.getName(), username));
    }

    private ResponseEntity<IssuedPassword> passwordResponse(HttpStatus status, UserManagement.IssuedPassword issued) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new IssuedPassword(UserView.from(issued.user()), issued.temporaryPassword()));
    }
}
