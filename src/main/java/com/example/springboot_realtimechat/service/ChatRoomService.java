package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MemberRepository memberRepository;
    private final InviteCodeGenerator inviteCodeGenerator;

    // 코드 충돌은 UNIQUE 제약이 막는다. 확률이 극히 낮아 재시도 횟수는 작게 잡는다.
    private static final int CODE_RETRY = 5;

    /**
     * ownerId가 null이면 주인 없는 방을 만든다. 테스트 픽스처와 시드 방이 그 경우다.
     * 생성자는 주인이자 첫 멤버가 된다 — 잠긴 방에서는 별도 join이 코드 없이 거부되기 때문이다.
     */
    @Transactional
    public ChatRoom create(String name, boolean isPrivate, Long ownerId) {
        Member owner = ownerId == null ? null : memberRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        ChatRoom saved = saveWithCode(name, isPrivate, owner);

        if (owner != null) {
            chatRoomMemberRepository.save(new ChatRoomMember(owner, saved));
        }
        return saved;
    }

    private ChatRoom saveWithCode(String name, boolean isPrivate, Member owner) {
        if (!isPrivate) {
            return chatRoomRepository.save(ChatRoom.publicRoom(name, owner));
        }
        for (int attempt = 0; attempt < CODE_RETRY; attempt++) {
            String code = inviteCodeGenerator.generate();
            if (chatRoomRepository.existsByInviteCode(code)) {
                continue;
            }
            try {
                // 코드는 로그에 남기지 않는다. 예외 메시지도 싣지 않고 조용히 재생성한다.
                return chatRoomRepository.saveAndFlush(ChatRoom.privateRoom(name, owner, code));
            } catch (DataIntegrityViolationException ignored) {
                // 사전 확인 이후 저장 사이의 경합으로 여전히 겹칠 수 있다. 다시 뽑는다.
            }
        }
        throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    public ChatRoom getChatRoomById(Long chatRoomId){
        return chatRoomRepository.findByIdAndDeletedAtIsNull(chatRoomId)
                .orElseThrow(()->new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    /**
     * 삭제된 방도 돌려준다. leave 전용이다 —
     * 삭제된 방을 못 찾으면 그 방의 멤버십 행을 사용자가 영영 지울 수 없다.
     */
    public ChatRoom getChatRoomByIdIncludingDeleted(Long chatRoomId){
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(()->new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    public List<ChatRoom> getAllChatRooms(){
        return chatRoomRepository.findByDeletedAtIsNull();
    }
}
