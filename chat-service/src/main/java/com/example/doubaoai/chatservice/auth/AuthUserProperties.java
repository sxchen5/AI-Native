package com.example.doubaoai.chatservice.auth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置文件中的演示账号（明文密码，仅用于本地/演示环境）。
 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthUserProperties {

    private List<UserEntry> users = new ArrayList<>();

    public List<UserEntry> getUsers() {
        return users;
    }

    public void setUsers(List<UserEntry> users) {
        this.users = users != null ? users : new ArrayList<>();
    }

    public static class UserEntry {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
