package com.ureca.billing.core.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class Users {
    private Long userId; // DB에서 auto_increment
    private String emailCipher; // AES-256-GCM 암호화된 이메일
    private String emailHash;   // SHA-256 해시
    private String phoneCipher; // AES-256-GCM 암호화된 전화번호
    private String phoneHash;   // SHA-256 해시
    private String name;
    private LocalDate birthDate;
    private UserStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public Users() {}

    // 생성자
    public Users(
    		String emailCipher,
    		String emailHash,
    		String phoneCipher,
    		String phoneHash, 
    		String name,
            LocalDate birthDate, 
            UserStatus status) {
        this.emailCipher = emailCipher;
        this.emailHash = emailHash;
        this.phoneCipher = phoneCipher;
        this.phoneHash = phoneHash;
        this.name = name;
        this.birthDate = birthDate;
        this.status = status;
    }

    // Getter / Setter
    public Long getUserId() { 
        return userId; 
    }
    public void setUserId(Long userId) { 
        this.userId = userId; 
    }

    public String getEmailCipher() { 
        return emailCipher; 
    }
    public void setEmailCipher(String emailCipher) {
        this.emailCipher = emailCipher;
    }

    public String getEmailHash() { 
        return emailHash; 
    }
    public void setEmailHash(String emailHash) {
        this.emailHash = emailHash;
    }

    public String getPhoneCipher() { 
        return phoneCipher; 
    }
    public void setPhoneCipher(String phoneCipher) {
        this.phoneCipher = phoneCipher;
    }

    public String getPhoneHash() { 
        return phoneHash; 
    }
    public void setPhoneHash(String phoneHash) {
        this.phoneHash = phoneHash;
    }

    public String getName() { 
        return name; 
    }
    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getBirthDate() { 
        return birthDate; 
    }
    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public UserStatus getStatus() { 
        return status; 
    }
    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() { 
        return createdAt; 
    }
    public void setCreatedAt(LocalDateTime createdAt) { 
        this.createdAt = createdAt; 
    }

    public LocalDateTime getUpdatedAt() { 
        return updatedAt; 
    }
    public void setUpdatedAt(LocalDateTime updatedAt) { 
        this.updatedAt = updatedAt; 
    }

}
