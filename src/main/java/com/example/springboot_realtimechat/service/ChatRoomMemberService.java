package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomMemberService {
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MemberService memberService;
    private final ChatRoomService chatRoomService;

    @Transactional
    public ChatRoomMember join(Long memberId, Long chatRoomId){
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);

        //이미 존재하는 지 확인하는거. (중복 방지)
        boolean exists = chatRoomMemberRepository
                .existsByMemberAndChatRoom(member, chatRoom);

        if(exists){
            throw new CustomException(ErrorCode.ALREADY_JOINED_ROOM);
        }

        ChatRoomMember chatRoomMember = new ChatRoomMember(member, chatRoom);
        try{
            // 트랜잭션이 끝나는 시점에 실제 SQL이 실행될 수 있어서, 중복 참여로 인한 unique 제약조건 예외가 try-catch 밖에서 발생할 수 있음.
            // saveAndFlush()는 저장한 뒤 즉시 DB에 반영을 시도.
            return chatRoomMemberRepository.saveAndFlush(chatRoomMember);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_ROOM);
        }

    }

    @Transactional
    public void leave(Long memberId, Long chatRoomId){
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);
        ChatRoomMember chatRoomMember = chatRoomMemberRepository
                .findByMemberAndChatRoom(member, chatRoom)
                        .orElseThrow(()->new CustomException(ErrorCode.NOT_JOINED_ROOM));

        chatRoomMemberRepository.delete(chatRoomMember);
    }

    public List<ChatRoomMember> getChatRoomMembersById(Long chatRoomId){
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);

        return chatRoomMemberRepository.findByChatRoom(chatRoom);
    }
}
