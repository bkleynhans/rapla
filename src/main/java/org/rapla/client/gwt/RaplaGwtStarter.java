package org.rapla.client.gwt;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Provider;

import org.rapla.client.gwt.view.RaplaPopups;
import org.rapla.framework.RaplaException;
import org.rapla.gwtjsonrpc.client.ExceptionDeserializer;
import org.rapla.gwtjsonrpc.client.impl.AbstractJsonProxy;
import org.rapla.gwtjsonrpc.client.impl.EntryPointFactory;
import org.rapla.storage.dbrm.LoginTokens;
import org.rapla.storage.dbrm.RaplaExceptionDeserializer;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.Cookies;
import com.google.gwt.user.client.History;
import com.google.gwt.user.client.Window;

/**
 * Created by Christopher on 02.09.2015.
 */
public class RaplaGwtStarter
{
    public static final String LOGIN_COOKIE = "raplaLoginToken";

    Bootstrap bootstrapProvider;

    @Inject
    public RaplaGwtStarter(Bootstrap bootstrapProvider)
    {
        this.bootstrapProvider = bootstrapProvider;
    }



    private LoginTokens getValidToken()
    {
        final Logger logger = Logger.getLogger("componentClass");
        String tokenString = Cookies.getCookie(LOGIN_COOKIE);
        if (tokenString != null)
        {
            // re request the server for refresh token
            LoginTokens token = LoginTokens.fromString(tokenString);
            boolean valid = token.isValid();
            if (valid)
            {
                logger.log(Level.INFO, "found valid cookie: " + tokenString);
                return token;
            }
        }
        logger.log(Level.INFO, "No valid login token found");
        return null;
    }

    public void startApplication()
    {
        LoginTokens token = getValidToken();
        if (token != null)
        {
            RaplaPopups.getProgressBar().setPercent(20);
            AbstractJsonProxy.setAuthThoken(token.getAccessToken());
            Scheduler.get().scheduleFixedDelay(new Scheduler.RepeatingCommand()
            {
                @Override
                public boolean execute()
                {
                    bootstrapProvider.load();
                    return false;
                }
            }, 100);
        }
        else
        {
            final String historyToken = History.getToken();
            final String appendig = historyToken != null && !historyToken.isEmpty() ? "&url=rapla.html#" + historyToken : "";
            Window.Location.replace(GWT.getModuleBaseURL() + "../rapla?page=auth" + appendig);
        }
    }

}
