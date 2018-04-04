package org.rapla.client.menu.gwt;

import io.reactivex.functions.Consumer;
import jsinterop.annotations.JsIgnore;
import jsinterop.annotations.JsType;
import org.rapla.client.PopupContext;
import org.rapla.client.menu.IdentifiableMenuEntry;
import org.rapla.components.i18n.I18nIcon;

@JsType
public class DefaultVueMenuItem implements IdentifiableMenuEntry, VueMenuItem {

  private final String id;
  private String icon;
  private Consumer<PopupContext> action;

  public DefaultVueMenuItem(final String label) {
    this.id = label;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public String getLabel() { return id; }

  @Override
  public String getIcon() {
    return icon;
  }

  @Override
  public Consumer<PopupContext> getAction() {
    return action;
  }

  @Override
  public Object getComponent() {
    return this;
  }

  @Override
  public void fireAction(PopupContext context) throws Exception {
    action.accept(context);
  }

  @JsIgnore
  public DefaultVueMenuItem action(Consumer<PopupContext> action) {
    this.action = action;
    return this;
  }

  @JsIgnore
  public DefaultVueMenuItem icon(final I18nIcon icon) {
      this.icon = icon == null ? null : icon.getId();
    return this;
  }
}
