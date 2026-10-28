package com.lexinote.nativeapp;

import java.io.IOException;
import java.util.Locale;

/** Service-owned recovery instruction; never read from arbitrary provider responses. */
public final class TokenDanceRecovery extends IOException {
  public final TokenDanceProtocol.RecoveryAction action;

  public TokenDanceRecovery(TokenDanceProtocol.RecoveryAction action) {
    super(action == TokenDanceProtocol.RecoveryAction.TOP_UP_BALANCE ? "账户余额不足，充值到账后可返回重试。"
        : action == TokenDanceProtocol.RecoveryAction.REAUTHORIZE_API_KEY ? "TokenDance 授权已失效，请重新连接。"
        : "授权的周期额度已用完，可等待额度刷新，或重新授权调整额度。");
    this.action = action;
  }

  public String actionId() { return action.name().toLowerCase(Locale.ROOT); }

  public static TokenDanceRecovery from(String base, String header) {
    TokenDanceProtocol.RecoveryAction action = TokenDanceProtocol.readRecoveryAction(base, header);
    return action == null ? null : new TokenDanceRecovery(action);
  }

  public static TokenDanceRecovery find(Throwable error) {
    for (int i = 0; error != null && i < 10; i++, error = error.getCause())
      if (error instanceof TokenDanceRecovery) return (TokenDanceRecovery) error;
    return null;
  }
}
