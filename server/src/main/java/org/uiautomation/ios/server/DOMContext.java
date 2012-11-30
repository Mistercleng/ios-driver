/*
 * Copyright 2012 ios-driver committers.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.uiautomation.ios.server;

import java.util.List;
import java.util.logging.Logger;

import org.json.JSONObject;
import org.openqa.selenium.TimeoutException;
import org.uiautomation.ios.mobileSafari.EventListener;
import org.uiautomation.ios.mobileSafari.NodeId;
import org.uiautomation.ios.mobileSafari.events.ChildNodeRemoved;
import org.uiautomation.ios.mobileSafari.events.Event;
import org.uiautomation.ios.mobileSafari.events.EventHistory;
import org.uiautomation.ios.mobileSafari.events.inserted.ChildIframeInserted;
import org.uiautomation.ios.webInspector.DOM.RemoteWebElement;

public class DOMContext implements EventListener {

  private static final Logger log = Logger.getLogger(DOMContext.class.getName());

  private volatile boolean pageLoaded = false;

  private volatile boolean isReady = true;
  private NodeId parent;

  private final ServerSideSession session;

  private RemoteWebElement window;
  private RemoteWebElement document;
  private RemoteWebElement iframe;

  private boolean isOnMainFrame = true;
  private RemoteWebElement mainDocument;
  private RemoteWebElement mainWindow;

  private final EventHistory eventHistory = new EventHistory();

  public RemoteWebElement getDocument() {
    int cpt = 0;
    while (!isReady) {
      cpt++;
      if (cpt > 20) {
        isReady = true;
        throw new TimeoutException("doc not ready.");
      }
      try {
        // System.err.println("cannot get document. Something is happening.");
        Thread.sleep(250);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    return document;
  }

  public RemoteWebElement getWindow() {
    return window;
  }

  public void reset() {
    window = null;
    document = null;
    iframe = null;
    mainDocument = null;
  }

  // TODO freynaud. Cleanup. A reference to the main document of the page needs
  // to be kept.
  // calling getDocument again to have the document after siwtching to an iframe
  // breaks the nodeId reference.
  public void setCurrentFrame(RemoteWebElement iframe, RemoteWebElement document, RemoteWebElement window) {
    this.iframe = iframe;
    this.document = document;
    this.window = window;

    if (iframe != null) {
      isOnMainFrame = false;
    } else {
      isOnMainFrame = true;
    }

    // switchToDefaultContent. revert to main document if it was set.
    if (iframe == null && document == null) {
      this.document = mainDocument;
      this.window = mainWindow;
    }

    // setting the main document for the first time
    if (iframe == null && document != null) {
      mainDocument = document;
      mainWindow = window;
    }

  }

  public boolean isOnMainFrame() {
    return isOnMainFrame;
  }

  public RemoteWebElement getCurrentFrame() {
    return iframe;
  }

  @Override
  public void onPageLoad() {
    pageLoaded = true;
    reset();
  }

  public void waitForPageToLoad(long timeout) {
    long start = System.currentTimeMillis();
    long deadLine = start + timeout;
    while (!pageLoaded) {
      if (System.currentTimeMillis() > deadLine) {
        throw new TimeoutException("failed to load the page after " + timeout + " ms.");
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        // ignore
      }
    }
    pageLoaded = false;
    return;
  }

  public DOMContext(ServerSideSession session) {
    this.session = session;
  }

  @Override
  public synchronized void domHasChanged(Event e) {
    try {

      if (e instanceof ChildNodeRemoved) {
        ChildNodeRemoved removed = (ChildNodeRemoved) e;
        if (iframe != null ? removed.getNode().equals(iframe.getNodeId()) : false) {
          isReady = false;
          parent = removed.getParent();
          log.fine("current frame " + iframe.getNodeId() + " is gone.Parent = " + parent);
          List<ChildIframeInserted> newOnes = eventHistory.getInsertedFrames(parent);
          if (newOnes.size() == 0) {
            return;
          } else if (newOnes.size() == 1) {
            Event newFrame = newOnes.get(0);
            assignNewFrameFromEvent((ChildIframeInserted) newFrame);
            eventHistory.removeEvent(newFrame);
          } else {
            log.warning("there should be only 1 newly created frame with parent =" + parent + ". Found "
                + newOnes.size());
          }
        }
        return;
      }

      if (e instanceof ChildIframeInserted) {
        ChildIframeInserted newFrame = (ChildIframeInserted) e;
        // are we waiting for something ?
        if (isReady) {
          eventHistory.add(newFrame);
          return;
        } else {
          // is it the new node we're looking for ?
          if (parent.equals(newFrame.getParent())) {
            log.fine("the new node is here :" + newFrame.getNode());
            assignNewFrameFromEvent(newFrame);
          }
        }
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  private void assignNewFrameFromEvent(ChildIframeInserted newFrameEvent) throws Exception {
    RemoteWebElement frame = new RemoteWebElement(newFrameEvent.getNode(), session);
    RemoteWebElement document = new RemoteWebElement(newFrameEvent.getContentDocument(), session);
    RemoteWebElement window = frame.getContentWindow();
    setCurrentFrame(frame, document, window);
    isReady = true;
  }

  @Override
  public void frameDied(JSONObject message) {
    // if that's the one we're working on, deselect it.
    if (iframe != null) {
      if (!iframe.exists()) {
        log.fine("the current frame is dead. Will need to switch to default content or another frame before being able to do anything.");
        isReady = true;
      }
    }

  }

}
