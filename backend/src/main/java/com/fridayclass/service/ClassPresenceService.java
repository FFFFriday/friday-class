package com.fridayclass.service;

import com.fridayclass.entity.User;
import com.fridayclass.repository.UserRepository;
import com.fridayclass.ws.SessionRegistry;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 课堂实时在线名单（查询侧）。
 *
 * <p>数据源是 {@link SessionRegistry} 的<b>内存态</b>，不是数据库——
 * 理由见 {@code SessionParticipantService} 的类注释：数据库那行在浏览器崩溃、
 * 拔网线时不会更新，拿它当在线依据只会越报越多。
 *
 * <p>为什么需要这个接口：老师端如果只靠 {@code presence.join} 事件累积名单，
 * 只能看到<b>自己连上之后</b>才进来的人——老师一刷新页面，名单就空了，
 * 而学生其实全都在。所以进课堂时要能一次性问「现在有谁在」。
 */
@Service
public class ClassPresenceService {

    private final SessionRegistry registry;
    private final UserRepository userRepository;

    public ClassPresenceService(SessionRegistry registry, UserRepository userRepository) {
        this.registry = registry;
        this.userRepository = userRepository;
    }

    /**
     * 当前在线的人。
     *
     * <p>只做一次批量查名字，不在循环里查库——名单短也经不起 N+1 的习惯。
     * 用户可能已被删除（软删除后 {@code findAllById} 仍会返回，但业务上不该出现），
     * 查不到的跳过即可，不能让一个脏 ID 把整个名单接口打挂。
     */
    public List<OnlineUser> onlineUsers(Long sessionId) {
        Set<Long> ids = registry.onlineUserIds(sessionId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllById(ids).stream()
                .map(user -> new OnlineUser(
                        user.getId(),
                        user.getNickname(),
                        user.getRole() == null ? null : user.getRole().name()))
                .sorted(Comparator.comparing(OnlineUser::role).thenComparing(OnlineUser::userId))
                .toList();
    }

    /** 在线名单里的一项。 */
    public record OnlineUser(Long userId, String nickname, String role) {
    }
}
