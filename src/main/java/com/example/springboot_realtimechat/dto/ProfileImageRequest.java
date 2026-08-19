package com.example.springboot_realtimechat.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfileImageRequest {
    @NotBlank
    @Size(max = 500)   // profile_image_url 컬럼 길이와 같게 둬 초과 값이 저장 단계까지 가지 않게 한다
    private String imageUrl;
}
