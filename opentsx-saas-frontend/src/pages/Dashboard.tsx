/**
 * OpenTSx Flow Composer: the page a signed-in user lands on. It lists the flows of the account and is the place
 * to open one in the editor (the workbench), to create, rename, copy and delete flows, and to start and stop the
 * Smart Topic of a flow.
 */

import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import FlowComposer from '../workbench/FlowComposer';
import { useFlow } from '../workbench/flow';
import '../workbench/workbench.css';

export default function Dashboard() {
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  // The first flow opens in the editor of this tab, the others in tabs of their own.
  const open = (ids: number[]) => {
    const [here, ...others] = ids;
    for (const id of others) window.open(`/workbench?flow=${id}`, '_blank');
    // the stored flow replaces what the editor holds from an earlier visit
    if (useFlow.getState().savedId === here) useFlow.getState().setSavedId(null);
    navigate(`/workbench?flow=${here}`);
  };

  const create = () => {
    useFlow.getState().clear();
    navigate('/workbench');
  };

  const forget = (ids: number[]) => {
    const id = useFlow.getState().savedId;
    if (id !== null && ids.includes(id)) useFlow.getState().clear();
  };

  return (
    <div className="wb wb-page">
      <header className="wb-toolbar">
        <h1>OpenTSx Flow Composer</h1>
        <span className="wb-grow" />
        <span className="wb-muted">{user?.full_name || user?.email}</span>
        <Link to="/workbench">Editor</Link>
        <Link to="/flow-builder" title="The earlier visual flow builder; its flows cannot be opened in the editor">Flow builder</Link>
        <button onClick={handleLogout}>Logout</button>
      </header>
      <FlowComposer base="/retrieval" currentId={useFlow.getState().savedId} replaces={false} onOpen={open} onNew={create} onDeleted={forget} />
    </div>
  );
}
