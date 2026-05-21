package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MessageRepository messageRepository;

    public List<Member> getMemberList(){
        return memberRepository.findAll();
    }

    public Member getMemberById(Long id){
        return memberRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Transactional
    public Member create(String email, String password, String nickname){
        if (memberRepository.findByEmail(email).isPresent()) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }
        String encodedPassword = passwordEncoder.encode(password);
        Member member = new Member(email, encodedPassword, nickname);
        return memberRepository.save(member);
    }

    @Transactional
    public void delete(Long id){
        Member member = memberRepository.findById(id)
                .orElseThrow(()-> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        chatRoomMemberRepository.deleteByMember(member);
        messageRepository.deleteByMember(member);
        memberRepository.delete(member);
    }
}
