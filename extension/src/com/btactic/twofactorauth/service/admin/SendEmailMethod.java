/*
 * ***** BEGIN LICENSE BLOCK *****
 * Maldua Zimbra 2FA Extension
 * Copyright (C) 2025 BTACTIC, S.C.C.L.
 *
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2008, 2009, 2010, 2013, 2014 Zimbra, Inc.
 * 
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */
package com.btactic.twofactorauth.service.admin;

import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import com.zimbra.common.mime.MimeConstants;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.Element;
import com.zimbra.common.util.L10nUtil;
import com.zimbra.common.util.L10nUtil.MsgKey;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.AuthToken.Usage;
import com.zimbra.cs.account.Config;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.mailbox.calendar.Util;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.MailboxManager;
import com.zimbra.cs.mailbox.OperationContext;
import com.zimbra.cs.service.admin.AdminDocumentHandler;
import com.zimbra.cs.service.AuthProvider;
import com.zimbra.cs.servlet.util.AuthUtil;
import com.zimbra.cs.util.AccountUtil;
import com.zimbra.soap.admin.message.SendTwoFactorAuthCodeRequest;
import com.zimbra.soap.admin.message.SendTwoFactorAuthCodeRequest.SendTwoFactorAuthCodeAction;
import com.zimbra.soap.admin.message.SendTwoFactorAuthCodeResponse;
import com.zimbra.soap.admin.message.SendTwoFactorAuthCodeResponse.SendTwoFactorAuthCodeStatus;
import com.zimbra.soap.DocumentHandler;
import com.zimbra.soap.JaxbUtil;
import com.zimbra.soap.ZimbraSoapContext;

import com.btactic.twofactorauth.ZetaTwoFactorAuth;

import com.zimbra.common.util.ZimbraLog;

public class SendEmailMethod extends TwoFactorAuthMethod {

    @Override
    protected SendTwoFactorAuthCodeStatus doMethod(Element request, Map<String, Object> context)
            throws ServiceException {
        Provisioning prov = Provisioning.getInstance();
        ZimbraSoapContext zsc = AdminDocumentHandler.getZimbraSoapContext(context);
        SendTwoFactorAuthCodeRequest req = JaxbUtil.elementToJaxb(request);

        AuthToken at = AuthUtil.getAuthToken(request, zsc);
        Account authTokenAcct = AuthProvider.validateAuthToken(prov, at, false, Usage.TWO_FACTOR_AUTH);

        String recoveryEmail = authTokenAcct.getPrefPasswordRecoveryAddress();
        boolean emailIsSent = false;
        if (recoveryEmail != null) {
          try {
            ZetaTwoFactorAuth manager = new ZetaTwoFactorAuth(authTokenAcct);

            manager.storeEmailCode();
            String code = manager.getEmailCode();
            long expiryTime = manager.getEmailExpiryTime();

            Mailbox mbox = MailboxManager.getInstance().getMailboxByAccountId(authTokenAcct.getId(), false);
            OperationContext octxt = new OperationContext(authTokenAcct);
            sendEmail(code, expiryTime, recoveryEmail, authTokenAcct, mbox, zsc, octxt);

            emailIsSent = true;
          } catch (ServiceException e) {
            emailIsSent = false;
          }
        } else {
          throw ServiceException.FAILURE("Non supported wizard input.", null);
        }

        // TODO: Add logic for when sending email cannot be done properly.
        if (emailIsSent) {
          return SendTwoFactorAuthCodeStatus.SENT;
        } else {
          return SendTwoFactorAuthCodeStatus.NOT_SENT;
        }
    }

    // TODO: Move to an Util class as an static method
    private String getDomainGlobalDefaultValue(Account account, String attribute, MsgKey defaultTranslation, Object... defaultArgs) throws ServiceException {

        String attributeValue;

        Locale locale = account.getLocale();

        Domain domain = account.getProvisioning().getDomain(account);
        String domainAttribute = domain.getAttr(attribute);

        Config config = Provisioning.getInstance().getConfig(attribute);
        String globalConfigAttribute = config.getAttr(attribute, null);

        if ( (domainAttribute != null) && (!(domainAttribute.isEmpty())) ) {
          attributeValue = MessageFormat.format(domainAttribute, defaultArgs);
        } else if ( (globalConfigAttribute != null) && (!(globalConfigAttribute.isEmpty())) ) {
          attributeValue = MessageFormat.format(globalConfigAttribute, defaultArgs);
        } else {
          attributeValue = L10nUtil.getMessage(defaultTranslation, locale, defaultArgs);
        }
        return attributeValue;

    }

    public void sendEmail(String code, long expiryTime, String toEmail, Account account, Mailbox mbox,
            ZimbraSoapContext zsc, OperationContext octxt) throws ServiceException {
        // Inspired from sendAndStoreTwoFactorAuthAccountCode function from EmailChannel.java file
        String ownerAcctDisplayName = account.getDisplayName();
        if (ownerAcctDisplayName == null) {
            ownerAcctDisplayName = account.getName();
        }
        String charset = account.getAttr(Provisioning.A_zimbraPrefMailDefaultCharset, MimeConstants.P_CHARSET_UTF8);
        try {
            DateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z");
            format.setTimeZone(TimeZone.getTimeZone(Util.getAccountTimeZone(account).getID()));
            String dateTime = format.format(expiryTime);
            if (ZimbraLog.misc.isDebugEnabled()) {
                ZimbraLog.misc.debug(
                        "TwoFactorAuth:SendEmailMethod:SendEmail: Expiry of two-factor auth email address verification code sent to %s: %s",
                        toEmail, dateTime);
                ZimbraLog.misc.debug(
                        "TwoFactorAuth:SendEmailMethod:SendEmail: Last 3 characters of two-factor auth email verification code sent to %s: %s",
                        toEmail,
                        code.substring(5));
            }
            String subject = this.getDomainGlobalDefaultValue(account, "zimbraTwoFactorCodeEmailSubject", MsgKey.twoFactorAuthCodeEmailSubject, ownerAcctDisplayName);
            String mimePartText = this.getDomainGlobalDefaultValue(account, "zimbraTwoFactorCodeEmailBodyText", MsgKey.twoFactorAuthCodeEmailBodyText, code, dateTime);
            String mimePartHtml = this.getDomainGlobalDefaultValue(account, "zimbraTwoFactorCodeEmailBodyHtml", MsgKey.twoFactorAuthCodeEmailBodyHtml, code, dateTime);

            MimeMultipart mmp = AccountUtil.generateMimeMultipart(mimePartText, mimePartHtml, null);
            MimeMessage mm = AccountUtil.generateMimeMessage(account, account, subject, charset, null, null,
                    toEmail, mmp);
            mbox.getMailSender().sendMimeMessage(octxt, mbox, false, mm, null, null, null, null, false);
        } catch (MessagingException e) {
            ZimbraLog.misc.warn("Failed to send two-factor auth email code to email ID: '"
                    + toEmail + "'", e);
            throw ServiceException.FAILURE("Failed to send two-factor auth email code to email ID: "
                    + toEmail, e);
        }
    }

    protected SendTwoFactorAuthCodeAction getAction() {
        return SendTwoFactorAuthCodeAction.EMAIL;
    }

}
