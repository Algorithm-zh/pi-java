package com.piagent.application.testsupport;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.port.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用层测试用的内存账号仓储。
 */
public final class InMemoryUserRepository implements UserRepository {

    private final List<AppUser> users = new ArrayList<>();

    @Override
    public Optional<AppUser> findById(UserId id) {
        return users.stream().filter(user -> user.id().equals(id)).findFirst();
    }

    @Override
    public List<AppUser> findByUsername(String username) {
        return users.stream().filter(user -> user.username().equals(username)).toList();
    }

    @Override
    public long count() {
        return users.size();
    }

    @Override
    public void insert(AppUser user) {
        users.removeIf(existing -> existing.id().equals(user.id()));
        users.add(user);
    }
}
