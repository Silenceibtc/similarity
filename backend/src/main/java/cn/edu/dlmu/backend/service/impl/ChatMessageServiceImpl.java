package cn.edu.dlmu.backend.service.impl;

import cn.edu.dlmu.backend.common.ErrorCode;
import cn.edu.dlmu.backend.exception.BusinessException;
import cn.edu.dlmu.backend.mapper.ChatMessageMapper;
import cn.edu.dlmu.backend.mapper.GroupChatMapper;
import cn.edu.dlmu.backend.mapper.GroupMemberMapper;
import cn.edu.dlmu.backend.model.domain.ChatMessage;
import cn.edu.dlmu.backend.model.domain.FriendRelation;
import cn.edu.dlmu.backend.model.domain.GroupChat;
import cn.edu.dlmu.backend.model.domain.GroupMember;
import cn.edu.dlmu.backend.model.vo.ChatSessionVO;
import cn.edu.dlmu.backend.service.ChatMessageService;
import cn.edu.dlmu.backend.service.FriendRelationService;
import cn.edu.dlmu.backend.service.GroupChatService;
import cn.edu.dlmu.backend.service.GroupMemberService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    @Resource
    private FriendRelationService friendRelationService;

    @Resource
    private GroupChatService groupChatService;

    @Resource
    private GroupMemberService groupMemberService;

    @Override
    public boolean sendMessage(Integer chatType, Long senderId, Long receiverId, String content, Integer messageType) {
        // 校验单聊时是否为好友关系
        if (chatType == 0) {
            QueryWrapper<FriendRelation> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userId", senderId)
                    .eq("friendId", receiverId)
                    .eq("isAgreed", 1);
            if (friendRelationService.count(queryWrapper) == 0) {
                throw new BusinessException(ErrorCode.NO_AUTH, "非好友关系，无法发送消息");
            }
        }
        // 校验群聊时是否为群成员
        if (chatType == 1) {
            QueryWrapper<GroupMember> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("groupId", receiverId)
                    .eq("userId", senderId);
            if (groupMemberService.count(queryWrapper) == 0) {
                throw new BusinessException(ErrorCode.NO_AUTH, "非群成员，无法发送消息");
            }
        }
        // 插入消息记录
        ChatMessage message = new ChatMessage();
        message.setChatType(chatType);
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setContent(content);
        message.setMessageType(messageType);
        message.setSendTime(new Date());
        return save(message);
    }

    @Override
    public List<ChatSessionVO> getChatSessions(Long userId) {
        // 1. 获取单聊会话（好友列表）
        List<ChatSessionVO> singleChats = friendRelationService.getFriendList(userId).stream()
                .map(friend -> {
                    ChatSessionVO vo = new ChatSessionVO();
                    vo.setSessionId(friend.getId());
                    vo.setSessionType(0); // 单聊
                    vo.setName(friend.getUsername());
                    vo.setAvatarUrl(friend.getAvatarUrl());
                    // 查询最后一条消息（示例，可优化）
                    QueryWrapper<ChatMessage> queryWrapper = new QueryWrapper<>();
                    queryWrapper.and(q -> q.eq("chatType", 0)
                                    .and(q2 -> q2.eq("senderId", userId).eq("receiverId", friend.getId())
                                            .or().eq("senderId", friend.getId()).eq("receiverId", userId)))
                            .orderByDesc("sendTime")
                            .last("LIMIT 1");
                    ChatMessage lastMessage = getOne(queryWrapper);
                    vo.setLastMessage(lastMessage != null ? lastMessage.getContent() : "");
                    vo.setLastMessageTime(lastMessage != null ? lastMessage.getSendTime() : null);
                    return vo;
                })
                .collect(Collectors.toList());

        // 2.获取群聊会话（已加入的群）
        QueryWrapper<GroupMember> memberQuery = new QueryWrapper<>();
        memberQuery.eq("userId", userId)
                .eq("isDelete", 0);
        List<GroupMember> groupMembers = groupMemberService.list(memberQuery);
        List<ChatSessionVO> groupChats = groupMembers.stream()
                .map(member -> {
                    GroupChat group = groupChatService.getById(member.getGroupId());
                    ChatSessionVO vo = new ChatSessionVO();
                    vo.setSessionId(group.getId());
                    vo.setSessionType(1); // 群聊
                    vo.setName(group.getGroupName());
                    vo.setAvatarUrl(null); // 群头像可单独设计字段
                    // 查询最后一条消息（示例）
                    QueryWrapper<ChatMessage> queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("chatType", 1)
                            .eq("receiverId", group.getId())
                            .orderByDesc("sendTime")
                            .last("LIMIT 1");
                    ChatMessage lastMessage = getOne(queryWrapper);
                    vo.setLastMessage(lastMessage != null ? lastMessage.getContent() : "");
                    vo.setLastMessageTime(lastMessage != null ? lastMessage.getSendTime() : null);
                    return vo;
                })
                .collect(Collectors.toList());

        // 合并单聊和群聊会话，按最后消息时间排序
        List<ChatSessionVO> allSessions = new ArrayList<>();
        allSessions.addAll(singleChats);
        allSessions.addAll(groupChats);
        allSessions.sort((a, b) -> b.getLastMessageTime().compareTo(a.getLastMessageTime()));
        return allSessions;
    }
}