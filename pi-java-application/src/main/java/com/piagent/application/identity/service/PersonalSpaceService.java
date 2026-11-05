package com.piagent.application.identity.service;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.SpaceMember;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.TenantKind;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.port.MembershipRepository;
import com.piagent.domain.identity.port.TenantRepository;
import com.piagent.domain.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 个人空间协作器：为每个账号保证恰有一个个人空间。
 *
 * <p>新账号在创建时立即获得个人空间；升级前的老账号在下一次登录时补建。
 * 个人空间短名由登录名派生并加随机后缀，避免与团队空间或同名账号冲突。</p>
 */
@Service
class PersonalSpaceService {

    private static final String PREFIX = "u-";
    private static final int MAX_SLUG_LENGTH = 32;
    private static final int MAX_NAME_LENGTH = 128;

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建协作器。
     *
     * @param tenants 空间仓储
     * @param memberships 成员仓储
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    PersonalSpaceService(TenantRepository tenants, MembershipRepository memberships,
            IdGenerator idGenerator, Clock clock) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 确保账号拥有个人空间；已存在时直接返回。
     *
     * @param user 账号
     * @return 个人空间
     */
    synchronized Tenant ensurePersonal(AppUser user) {
        Optional<Tenant> existing = personalOf(user.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        Tenant tenant = Tenant.create(TenantId.newId(idGenerator), uniqueSlug(user),
                personalName(user), TenantKind.PERSONAL, now);
        tenants.insert(tenant);
        memberships.insert(SpaceMember.create(tenant.id(), user.id(), UserRole.ADMIN, now));
        return tenant;
    }

    private Optional<Tenant> personalOf(UserId userId) {
        for (SpaceMember member : memberships.findByUser(userId)) {
            Optional<Tenant> tenant = tenants.findById(member.tenantId());
            if (tenant.isPresent() && tenant.get().isPersonal() && !tenant.get().isArchived()) {
                return tenant;
            }
        }
        return Optional.empty();
    }

    private String personalName(AppUser user) {
        String name = user.displayName() + " 的空间";
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }

    private String uniqueSlug(AppUser user) {
        String base = sanitize(user.username());
        String suffix = user.id().toString();
        suffix = suffix.substring(suffix.length() - 4).toLowerCase(Locale.ROOT);
        int attempt = 1;
        String candidate = compose(base, suffix, attempt);
        while (tenants.findBySlug(candidate).isPresent()) {
            attempt++;
            candidate = compose(base, suffix, attempt);
        }
        return candidate;
    }

    private String compose(String base, String suffix, int attempt) {
        String tail = attempt == 1 ? "-" + suffix : "-" + suffix + "-" + attempt;
        int maxBase = MAX_SLUG_LENGTH - PREFIX.length() - tail.length();
        String truncated = base.length() <= maxBase ? base : base.substring(0, maxBase);
        truncated = truncated.replaceAll("-+$", "");
        if (truncated.isEmpty()) {
            truncated = "me";
        }
        return PREFIX + truncated + tail;
    }

    private String sanitize(String username) {
        String base = username.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        base = base.replaceAll("^-+|-+$", "");
        return base.isEmpty() ? "me" : base;
    }
}
