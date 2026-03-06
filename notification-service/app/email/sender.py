import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from pathlib import Path

from app.config import Settings


class EmailSender:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        template_path = Path(__file__).parent / 'templates' / 'verify_email.html'
        self._verify_template = template_path.read_text(encoding='utf-8')

    def send_verification_email(self, to_email: str, username: str, verification_code: str) -> None:
        subject = 'Verify your email'
        html_body = (
            self._verify_template
            .replace('{{username}}', username)
            .replace('{{verificationCode}}', verification_code)
        )

        msg = MIMEMultipart('alternative')
        msg['From'] = self._settings.smtp_from
        msg['To'] = to_email
        msg['Subject'] = subject

        text_body = (
            f'Hello {username},\n\n'
            'Your verification code is:\n\n'
            f'{verification_code}\n\n'
            'This code expires in 10 minutes.\n'
        )

        msg.attach(MIMEText(text_body, 'plain'))
        msg.attach(MIMEText(html_body, 'html'))

        with smtplib.SMTP(self._settings.smtp_host, self._settings.smtp_port, timeout=15) as server:
            server.starttls()
            server.login(self._settings.smtp_username, self._settings.smtp_password)
            server.sendmail(self._settings.smtp_from, [to_email], msg.as_string())
