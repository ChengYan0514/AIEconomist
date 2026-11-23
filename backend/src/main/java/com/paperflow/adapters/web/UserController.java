package com.paperflow.adapters.web;

import com.paperflow.account.UserManagement;
import com.paperflow.account.model.PlatformUser;
import com.paperflow.account.model.Role;
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

    record UserView(String username, String displayName, Role role, PlatformUser.Status status, OffsetDateTime createdAt) {
        static UserView from(PlatformUser user) {
            return new UserView(user.username(), user.displayName(), user.role(),
                    user.enabled() ? PlatformUser.Status.ACTIVE : PlatformUser.Status.DISABLED, user.createdAt());
        }
    }
    record CreateUser(String username, String displayName, Role role) {}
    record UpdateUser(String displayName, Role role) {}
    record CreatedUser(UserView user, String temporaryPassword) {}

    @GetMapping
    Page<UserView> list(@RequestParam(required = false) String q, @RequestParam(required = false) Role role,
                        @RequestParam(required = false) PlatformUser.Status status,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = users.list(q, role, status, Page.offset(page, size), size);
        return new Page<>(result.items().stream().map(UserView::from).toList(), page, size, result.total());
    }

    @PostMapping
    ResponseEntity<CreatedUser> create(@RequestBody CreateUser request, Principal actor) {
        var created = users.create(actor.getName(), request.username(), request.displayName(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new CreatedUser(UserView.from(created.user()), created.temporaryPassword()));
    }

    @PatchMapping("/{username}")
    UserView update(@PathVariable String username, @RequestBody UpdateUser request, Principal actor) {
        return UserView.from(users.update(actor.getName(), username, request.displayName(), request.role()));
    }
}
