package com.ureca.billing.core.entity;

import java.sql.Time;
import java.time.LocalDateTime;

public class UserNotificationPrefs {
	private Long prefId;	//auto_increment
	private Long userId;	//users table pk
	private String channel;		//random in(EMAIL,SMS,PUSH)
	private boolean enabled;
	private int priority;	//default 1
	private Time quietStart;	//설정 안했으면 NULL
	private Time quietEnd;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;
	
	public Long getPrefId() {
		return prefId;
	}
	public void setPrefId(Long prefId) {
		this.prefId = prefId;
	}
	public Long getUserId() {
		return userId;
	}
	public void setUserId(Long userId) {
		this.userId = userId;
	}
	public String getChannel() {
		return channel;
	}
	public void setChannel(String channel) {
		this.channel = channel;
	}
	public boolean getEnabled() {
		return enabled;
	}
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
	public int getPriority() {
		return priority;
	}
	public void setPriority(int priority) {
		this.priority = priority;
	}
	public Time getQuietStart() {
		return quietStart;
	}
	public void setQuietStart(Time quietStart) {
		this.quietStart = quietStart;
	}
	public Time getQuietEnd() {
		return quietEnd;
	}
	public void setQuietEnd(Time quietEnd) {
		this.quietEnd = quietEnd;
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
