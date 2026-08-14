package com.arena.alliance.user;

import com.arena.alliance.common.AllianceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String LOCAL_PREFIX = "local:";

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * LinuxDo 登录成功后落库；平台第一个用户自动成为管理员。
     */
    @Transactional
    public synchronized User loginFromLinuxDo(String linuxdoId, String username, String displayName,
                                              int trustLevel, boolean registrationOpen) {
        User user = repository.findByLinuxdoId(linuxdoId).orElse(null);
        if (user == null) {
            if (!registrationOpen) {
                throw AllianceException.forbidden("联盟当前未开放注册");
            }
            user = new User();
            user.setLinuxdoId(linuxdoId);
            if (repository.count() == 0) {
                user.setRole(User.Role.ADMIN);
                log.info("首位登录用户 {} 成为管理员", username);
            }
        }
        if (user.getStatus() == User.Status.BANNED) {
            throw AllianceException.forbidden("该账号已被封禁");
        }
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setTrustLevel(trustLevel);
        user.setLastLoginAt(Instant.now());
        return repository.save(user);
    }

    public User get(long id) {
        return repository.findById(id).orElseThrow(() -> AllianceException.notFound("用户不存在"));
    }

    /**
     * 账号密码注册。registerAllowed 由规则决定；用户数为 0 时始终放行（首个用户 = 管理员，
     * 解决全新部署无 LinuxDo 配置时的引导问题）。
     */
    @Transactional
    public synchronized User registerLocal(String username, String rawPassword, boolean registerAllowed) {
        String name = username == null ? "" : username.trim();
        if (!name.matches("[\\w\\u4e00-\\u9fa5-]{2,24}")) {
            throw AllianceException.badRequest("用户名需 2-24 位字母/数字/下划线/中文");
        }
        if (rawPassword == null || rawPassword.length() < 6) {
            throw AllianceException.badRequest("密码至少 6 位");
        }
        boolean firstUser = repository.count() == 0;
        if (!firstUser && !registerAllowed) {
            throw AllianceException.forbidden("管理员未开放账号密码注册");
        }
        if (repository.findFirstByUsername(name).isPresent()
                || repository.findByLinuxdoId(LOCAL_PREFIX + name).isPresent()) {
            throw AllianceException.badRequest("用户名已存在");
        }
        User user = new User();
        user.setLinuxdoId(LOCAL_PREFIX + name);
        user.setUsername(name);
        user.setDisplayName(name);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setLastLoginAt(Instant.now());
        if (firstUser) {
            user.setRole(User.Role.ADMIN);
            log.info("首位注册用户 {} 成为管理员", name);
        }
        return repository.save(user);
    }

    /** 账号密码登录 */
    @Transactional
    public User loginLocal(String username, String rawPassword) {
        String name = username == null ? "" : username.trim();
        User user = repository.findByLinuxdoId(LOCAL_PREFIX + name)
                .orElseThrow(() -> AllianceException.unauthorized("用户名或密码错误"));
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(rawPassword == null ? "" : rawPassword, user.getPasswordHash())) {
            throw AllianceException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() == User.Status.BANNED) {
            throw AllianceException.forbidden("该账号已被封禁");
        }
        user.setLastLoginAt(Instant.now());
        return repository.save(user);
    }

    public List<User> listAll() {
        return repository.findAll();
    }

    @Transactional
    public User kick(long userId) {
        User user = get(userId);
        user.setStatus(User.Status.KICKED);
        return repository.save(user);
    }

    @Transactional
    public User restore(long userId) {
        User user = get(userId);
        user.setStatus(User.Status.ACTIVE);
        user.setWarnings(0);
        return repository.save(user);
    }

    /** 记一次警告，返回累计次数 */
    @Transactional
    public int warn(long userId) {
        User user = get(userId);
        user.setWarnings(user.getWarnings() + 1);
        repository.save(user);
        return user.getWarnings();
    }

    @Transactional
    public void resetWarnings(long userId) {
        User user = get(userId);
        user.setWarnings(0);
        repository.save(user);
    }
}
