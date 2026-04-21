package com.example.doubaoai.chatservice.auth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

/**
 * 生成 PNG 验证码图片。
 */
@Component
public class CaptchaImageRenderer {

    private final SecureRandom random = new SecureRandom();

    public byte[] renderPng(String code) throws IOException {
        int w = 110;
        int h = 40;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 245, 245));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(200 + random.nextInt(40), 200 + random.nextInt(40), 200 + random.nextInt(40)));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(random.nextInt(w), random.nextInt(h), random.nextInt(w), random.nextInt(h));
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(40 + random.nextInt(80), 40 + random.nextInt(80), 40 + random.nextInt(80)));
            int x = 14 + i * 22 + random.nextInt(4);
            int y = 26 + random.nextInt(4);
            g.drawString(String.valueOf(code.charAt(i)), x, y);
        }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
