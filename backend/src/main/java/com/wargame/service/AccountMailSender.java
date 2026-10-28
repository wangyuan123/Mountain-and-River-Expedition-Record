package com.wargame.service;

import com.wargame.config.AccountEmailProperties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** 仅由受限模板和投递队列调用；SMTP 连接不进入账号事务。 */
@Component
public class AccountMailSender {
    private final AccountEmailProperties config;
    public AccountMailSender(AccountEmailProperties config) { this.config = config; }
    public void send(String recipient, String subject, String body) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getHost()); sender.setPort(config.getPort());
        sender.setUsername(config.getUsername()); sender.setPassword(config.getPassword());
        sender.setDefaultEncoding("UTF-8");
        var props = sender.getJavaMailProperties();
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.ssl.enable", Boolean.toString(config.isSsl()));
        props.setProperty("mail.smtp.starttls.enable", Boolean.toString(!config.isSsl()));
        props.setProperty("mail.smtp.starttls.required", Boolean.toString(!config.isSsl()));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        props.setProperty("mail.smtp.connectiontimeout", "5000");
        props.setProperty("mail.smtp.timeout", "5000");
        props.setProperty("mail.smtp.writetimeout", "5000");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(config.getFrom()); message.setTo(recipient);
        message.setSubject(subject); message.setText(body);
        sender.send(message);
    }
}
