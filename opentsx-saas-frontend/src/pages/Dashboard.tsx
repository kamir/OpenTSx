/**
 * OpenTSx Flow Composer: the page a signed-in user lands on. It lists the flows of the account and is the place
 * to open one in the editor (the workbench), to create, rename, copy and delete flows, and to start and stop the
 * Smart Topic of a flow.
 */

import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { RETRIEVAL_BASE } from '../services/api';
import { useAuthStore } from '../store/authStore';
import FlowComposer from '../workbench/FlowComposer';
import { forgetFlows, newFlow, openFlows } from '../workbench/composerActions';
import { useFlow } from '../workbench/flow';
import '../workbench/workbench.css';

export default function Dashboard() {
  const navigate = useNavigate();
  const { user, logout, error } = useAuthStore();
  const [notice, setNotice] = useState<string | null>(null);

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  // when the browser kept a tab from opening, the page stays to say so and the workbench keeps the flow it holds
  const open = (ids: number[]) => {
    const held = useFlow.getState().savedId;
    const blocked = openFlows(ids, () => undefined);
    if (blocked === 0) {
      navigate(`/workbench?flow=${ids[0]}`);
      return;
    }
    useFlow.getState().setSavedId(held);
    setNotice(`The browser kept ${blocked} of the flows from opening in tabs of their own, so this page stayed and did not open the first one either. Allow pop-ups for this page, or open them one at a time.`);
  };

  const create = () => {
    newFlow();
    navigate('/workbench');
  };

  return (
    <div className="wb wb-page">
      <header className="wb-toolbar">
        <h1>OpenTSx Flow Composer</h1>
        <span className="wb-grow" />
        <span className="wb-muted">{user?.full_name || user?.email}</span>
        <Link to="/systems" title="Systems with their signals, flows and models">Systems</Link>
        <Link to="/workbench">Workbench</Link>
        <Link to="/flow-builder" title="The earlier visual flow builder; its flows cannot be opened in the editor">Flow builder</Link>
        <button onClick={handleLogout}>Logout</button>
      </header>
      {error && <div className="wb-error" role="alert">{error}</div>}
      {notice && <div className="wb-error" role="alert">{notice}</div>}
      <FlowComposer base={RETRIEVAL_BASE} currentId={useFlow.getState().savedId} replaces={false} onOpen={open} onNew={create} onDeleted={(ids) => forgetFlows(ids, false)} />
    </div>
  );
}
