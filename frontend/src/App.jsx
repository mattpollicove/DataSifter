import { useCallback, useEffect, useRef, useState } from 'react'

const navItems = ['Dashboard', 'Workflows', 'Connectors', 'Security', 'Mapping', 'KeyVault', 'Audit Logs', 'Settings']

function App() {
  const [activeView, setActiveView] = useState('Dashboard')
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [loginUsername, setLoginUsername] = useState('')
  const [loginPassword, setLoginPassword] = useState('')
  const [authHeader, setAuthHeader] = useState('')
  const [session, setSession] = useState(null)
  const [loginError, setLoginError] = useState('')
  const [dataError, setDataError] = useState('')
  const [jobMessage, setJobMessage] = useState('')
  const [workflowMessage, setWorkflowMessage] = useState('')
  const [selectedWorkflowId, setSelectedWorkflowId] = useState(null)
  const [workflowDetail, setWorkflowDetail] = useState(null)
  const [workflowForm, setWorkflowForm] = useState({
    name: '',
    owner: '',
    status: 'active',
    schedule: ''
  })
  const [designerStages, setDesignerStages] = useState([])
  const [designerMappings, setDesignerMappings] = useState([])
  const [canvasNodes, setCanvasNodes] = useState([
    { id: 'source', type: 'source', name: 'LDAP source', config: 'employee_id', x: 30, y: 120 },
    { id: 'filter', type: 'filter', name: 'Filter', config: 'HR only', x: 230, y: 80 },
    { id: 'transform', type: 'transform', name: 'Normalize', config: 'full_name', x: 450, y: 150 },
    { id: 'secure', type: 'transform', name: 'Hash PII', config: 'email_hash', x: 650, y: 70 },
    { id: 'target', type: 'target', name: 'Warehouse', config: 'staging.user', x: 860, y: 130 }
  ])
  const [canvasEdges, setCanvasEdges] = useState([
    { id: 'e1', from: 'source', to: 'filter' },
    { id: 'e2', from: 'filter', to: 'transform' },
    { id: 'e3', from: 'transform', to: 'secure' },
    { id: 'e4', from: 'secure', to: 'target' }
  ])
  const [selectedNodeId, setSelectedNodeId] = useState('transform')
  const [draggingNodeId, setDraggingNodeId] = useState(null)
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 })
  const canvasRef = useRef(null)
  const canvasIdSequence = useRef(0)

  const apiFetch = useCallback(async (url, options = {}, credentials = authHeader) => {
    const headers = new Headers(options.headers ?? {})
    if (credentials) headers.set('Authorization', credentials)
    const method = (options.method ?? 'GET').toUpperCase()
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && !url.startsWith('/api/workers/')) {
      let csrf = decodeURIComponent(document.cookie.split('; ').find((cookie) => cookie.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=') ?? '')
      if (!csrf) {
        const tokenResponse = await fetch('/api/csrf', { headers: credentials ? { Authorization: credentials } : {} })
        if (!tokenResponse.ok) throw new Error(`Unable to obtain CSRF token (${tokenResponse.status})`)
        csrf = (await tokenResponse.json()).token
      }
      headers.set('X-XSRF-TOKEN', csrf)
    }
    return fetch(url, { ...options, headers })
  }, [authHeader])

  const responseJson = useCallback(async (url, options = {}, credentials = authHeader) => {
    const response = await apiFetch(url, options, credentials)
    if (options.optional && response.status === 403) return options.fallback
    if (!response.ok) throw new Error(`${url} failed with HTTP ${response.status}`)
    return response.status === 204 ? null : response.json()
  }, [apiFetch, authHeader])

  const handleLogin = async (event) => {
    event.preventDefault()
    setLoginError('')
    setLoading(true)
    const credentialBytes = new TextEncoder().encode(`${loginUsername}:${loginPassword}`)
    const encodedCredentials = window.btoa(Array.from(credentialBytes, (byte) => String.fromCharCode(byte)).join(''))
    const header = `Basic ${encodedCredentials}`
    try {
      const csrfResponse = await fetch('/api/csrf')
      if (!csrfResponse.ok) throw new Error(`Security token request failed (${csrfResponse.status})`)
      const dashboardResponse = await fetch('/api/dashboard', { headers: { Authorization: header } })
      if (!dashboardResponse.ok) throw new Error(
        dashboardResponse.status === 401 ? 'Invalid username or password.' : `Sign-in failed (${dashboardResponse.status}).`)
      setAuthHeader(header)
      await refreshData(header)
      setLoginPassword('')
    } catch (error) {
      setLoginError(error.message)
    } finally {
      setLoading(false)
    }
  }

  const handleLogout = () => {
    setAuthHeader('')
    setSession(null)
    setData(null)
    setActiveView('Dashboard')
  }

  const refreshData = useCallback(async (credentials = authHeader) => {
    if (!credentials) return
    try {
      const [currentSession, dashboard, workflows, connectors, audit, keyvault, security] = await Promise.all([
        responseJson('/api/session', {}, credentials),
        responseJson('/api/dashboard', {}, credentials),
        responseJson('/api/workflows', {}, credentials),
        responseJson('/api/connectors', {}, credentials),
        responseJson('/api/audit', { optional: true, fallback: [] }, credentials),
        responseJson('/api/keyvault', { optional: true, fallback: [] }, credentials),
        responseJson('/api/security', {}, credentials)
      ])

      setSession(currentSession)
      setData({ dashboard, workflows, connectors, audit, keyvault, security })
      setDataError('')
      if (workflows?.[0]?.id) {
        setSelectedWorkflowId(workflows[0].id)
      }
    } catch (error) {
      setDataError(error.message)
    }
  }, [authHeader, responseJson])

  useEffect(() => {
    if (!selectedWorkflowId) return

    responseJson(`/api/workflow/${selectedWorkflowId}`)
      .then((detail) => {
        setWorkflowDetail(detail)
        setWorkflowForm({
          name: detail.name,
          owner: detail.owner,
          status: detail.status,
          schedule: detail.schedule
        })
        setDesignerStages(detail.stages ?? [])
        setDesignerMappings(detail.fieldMappings ?? [])
        if (detail.canvasNodes?.length) {
          setCanvasNodes(detail.canvasNodes)
          setSelectedNodeId(detail.canvasNodes[0].id)
        }
        if (detail.canvasEdges) setCanvasEdges(detail.canvasEdges)
      })
      .catch((error) => {
        setWorkflowMessage(error.message)
      })
  }, [selectedWorkflowId, authHeader, responseJson])

  const nextCanvasId = (prefix) => `${prefix}-${++canvasIdSequence.current}`

  const handleWorkflowFieldChange = (event) => {
    const { name, value } = event.target
    setWorkflowForm((current) => ({ ...current, [name]: value }))
  }

  const handleStageFieldChange = (index, field, value) => {
    setDesignerStages((current) => current.map((stage, stageIndex) => (
      stageIndex === index ? { ...stage, [field]: value } : stage
    )))
  }

  const handleAddStage = () => {
    setDesignerStages((current) => [
      ...current,
      { name: `New stage ${current.length + 1}`, status: 'queued', duration: '00:00:00', records: '0' }
    ])
  }

  const handleSaveWorkflow = async () => {
    const updatedWorkflow = {
      ...workflowDetail,
      ...workflowForm,
      stages: designerStages,
      canvasNodes,
      canvasEdges,
      fieldMappings: designerMappings
    }
    try {
      const saved = await responseJson(`/api/workflow/${selectedWorkflowId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updatedWorkflow)
      })
      setWorkflowDetail({ ...updatedWorkflow, ...saved })
      setData((current) => ({
        ...current,
        workflows: current.workflows.map((workflow) => (
          workflow.id === selectedWorkflowId ? { ...workflow, ...saved } : workflow
        ))
      }))
      setWorkflowMessage('Workflow, canvas, and mapping saved.')
    } catch (error) {
      setWorkflowMessage(`Save failed: ${error.message}`)
    }
  }

  const handleUploadCsv = async (event) => {
    const file = event.target.files?.[0]
    if (!file || !selectedWorkflowId) return
    const body = new FormData()
    body.append('file', file)
    try {
      await responseJson(`/api/workflows/${selectedWorkflowId}/csv`, { method: 'POST', body })
      setWorkflowDetail((current) => ({ ...current, sourceCsvReady: true }))
      setWorkflowMessage(`${file.name} uploaded and linked to this workflow.`)
    } catch (error) {
      setWorkflowMessage(`CSV upload failed: ${error.message}`)
    } finally {
      event.target.value = ''
    }
  }

  const selectedNode = canvasNodes.find((node) => node.id === selectedNodeId) ?? canvasNodes[0]

  const handleNodeMouseDown = (event, nodeId) => {
    const node = canvasNodes.find((entry) => entry.id === nodeId)
    if (!node || !canvasRef.current) {
      return
    }

    const rect = canvasRef.current.getBoundingClientRect()
    setSelectedNodeId(nodeId)
    setDraggingNodeId(nodeId)
    setDragOffset({
      x: event.clientX - rect.left - node.x,
      y: event.clientY - rect.top - node.y
    })
  }

  const handleCanvasMouseMove = (event) => {
    if (!draggingNodeId || !canvasRef.current) return

    const rect = canvasRef.current.getBoundingClientRect()
    const nextX = Math.min(Math.max(event.clientX - rect.left - dragOffset.x, 20), rect.width - 170)
    const nextY = Math.min(Math.max(event.clientY - rect.top - dragOffset.y, 20), rect.height - 100)

    setCanvasNodes((current) => current.map((node) => (
      node.id === draggingNodeId
        ? { ...node, x: nextX, y: nextY }
        : node
    )))
  }

  const handleCanvasMouseUp = () => {
    setDraggingNodeId(null)
  }

  const handleAddNode = (type, position = null) => {
    const nextId = nextCanvasId(type)
    const baseNames = {
      source: 'Source',
      filter: 'Filter',
      transform: 'Transform',
      target: 'Target'
    }
    const baseConfig = {
      source: 'CSV input',
      filter: 'status = active',
      transform: 'normalize()',
      target: 'warehouse'
    }

    const nextNode = {
      id: nextId,
      type,
      name: baseNames[type],
      config: baseConfig[type],
      x: position ? position.x : 260 + (canvasNodes.length % 4) * 120,
      y: position ? position.y : 90 + (canvasNodes.length % 3) * 80
    }

    setCanvasNodes((current) => [...current, nextNode])
    setSelectedNodeId(nextId)

    if (canvasNodes.length > 0 && position) {
      const lastNode = canvasNodes[canvasNodes.length - 1]
      setCanvasEdges((current) => [
        ...current,
        { id: nextCanvasId('edge'), from: lastNode.id, to: nextId }
      ])
    }
  }

  const handlePaletteDragStart = (event, type) => {
    event.dataTransfer.setData('application/x-datasifter-node', type)
    event.dataTransfer.effectAllowed = 'copy'
  }

  const handleCanvasDrop = (event) => {
    event.preventDefault()
    const type = event.dataTransfer.getData('application/x-datasifter-node')
    if (!type) return

    const rect = canvasRef.current?.getBoundingClientRect()
    if (!rect) return

    const x = Math.min(Math.max(event.clientX - rect.left - 75, 20), rect.width - 170)
    const y = Math.min(Math.max(event.clientY - rect.top - 40, 20), rect.height - 100)

    handleAddNode(type, { x, y })
  }

  const handleNodeFieldChange = (field, value) => {
    if (!selectedNodeId) return

    setCanvasNodes((current) => current.map((node) => (
      node.id === selectedNodeId ? { ...node, [field]: value } : node
    )))
  }

  const handleMappingChange = (index, field, value) => {
    setDesignerMappings((current) => current.map((mapping, mappingIndex) => (
      mappingIndex === index ? { ...mapping, [field]: value } : mapping
    )))
  }

  const handleAddMapping = () => {
    setDesignerMappings((current) => [
      ...current,
      { sourceField: '', targetField: '', privacyAction: 'none' }
    ])
  }

  const handleExecutionFieldChange = (field, value) => {
    setWorkflowDetail((current) => ({ ...current, [field]: value }))
  }

  const handleDeleteSelectedNode = () => {
    if (!selectedNodeId) return

    const nextSelectedId = canvasNodes.find((node) => node.id !== selectedNodeId)?.id ?? null

    setCanvasNodes((current) => current.filter((node) => node.id !== selectedNodeId))
    setCanvasEdges((current) => current.filter((edge) => edge.from !== selectedNodeId && edge.to !== selectedNodeId))
    setSelectedNodeId(nextSelectedId)
  }

  const handleNodeClick = (nodeId, event) => {
    if (event?.shiftKey && selectedNodeId && selectedNodeId !== nodeId) {
      const alreadyConnected = canvasEdges.some((edge) => edge.from === selectedNodeId && edge.to === nodeId)
      if (!alreadyConnected) {
        setCanvasEdges((current) => [
          ...current,
          { id: nextCanvasId('edge'), from: selectedNodeId, to: nodeId }
        ])
      }
      setSelectedNodeId(nodeId)
      return
    }

    setSelectedNodeId(nodeId)
  }

  const handleRunJob = async () => {
    if (!selectedWorkflowId) {
      setJobMessage('Create or select a workflow before starting a job.')
      return
    }
    try {
      const result = await responseJson('/api/jobs/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: workflowDetail?.name ?? 'Manual sync',
          workflowId: selectedWorkflowId
        })
      })
      await refreshData()
      setJobMessage(`${result.name} accepted. ${result.message}`)
    } catch (error) {
      setJobMessage(`The job queue could not be updated: ${error.message}`)
    }
  }

  if (!authHeader) {
    return (
      <main className="login-shell">
        <form className="login-card" onSubmit={handleLogin}>
          <div className="brand-mark">N</div>
          <p className="eyebrow">DataSifter control plane</p>
          <h1>Sign in</h1>
          <label>
            Username
            <input autoComplete="username" value={loginUsername} onChange={(event) => setLoginUsername(event.target.value)} required />
          </label>
          <label>
            Password
            <input type="password" autoComplete="current-password" value={loginPassword} onChange={(event) => setLoginPassword(event.target.value)} required />
          </label>
          {loginError && <div className="status-banner error">{loginError}</div>}
          <button type="submit" className="primary-button" disabled={loading}>
            {loading ? 'Signing in...' : 'Sign in'}
          </button>
        </form>
      </main>
    )
  }

  if (loading || !data) {
    return <div className="loading-shell">{dataError || 'Loading DataSifter control plane...'}</div>
  }

  const isAdmin = session?.roles?.includes('ADMIN')
  const visibleNavItems = navItems.filter((item) => (item !== 'KeyVault' || isAdmin) && (item !== 'Audit Logs' || isAdmin || session?.roles?.includes('AUDITOR')))
  const canOperate = isAdmin || session?.roles?.includes('OPERATOR')

  const renderView = () => {
    if (activeView === 'Dashboard') {
      return (
        <>
          <section className="kpi-grid">
            {data.dashboard.metrics.map((metric) => (
              <article key={metric.label} className="kpi-card">
                <span>{metric.label}</span>
                <strong>{metric.value}</strong>
                <em className={metric.tone === 'up' ? 'gain' : 'loss'}>{metric.delta}</em>
              </article>
            ))}
          </section>

          <section className="content-grid">
            <div className="panel large-panel">
              <div className="panel-header">
                <div>
                  <p className="eyebrow">Active jobs</p>
                  <h2>Execution queue</h2>
                </div>
                <div className="panel-actions">
                  <button type="button" className="secondary-button">Pause</button>
                  {canOperate && (
                    <button type="button" className="primary-button" onClick={handleRunJob} disabled={!selectedWorkflowId}>Start</button>
                  )}
                </div>
              </div>

              {jobMessage && <div className="status-banner">{jobMessage}</div>}

              <div className="jobs-list">
                {data.dashboard.jobs.map((job) => (
                  <div key={job.name} className="job-card">
                    <div className="job-head">
                      <div>
                        <strong>{job.name}</strong>
                        <small>{job.source} → {job.target}</small>
                      </div>
                      <span className={`status-badge ${job.status}`}>{job.status}</span>
                    </div>
                    <div className="progress-track">
                      <span style={{ width: `${job.progress}%` }} />
                    </div>
                    <div className="job-meta">
                      <span>{job.progress}%</span>
                      <span>{job.success} success</span>
                      <span>{job.failed} failed</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div className="panel side-panel">
              <div className="panel-header compact">
                <div>
                  <p className="eyebrow">Alerts</p>
                  <h2>Watchlist</h2>
                </div>
              </div>
              <ul className="alert-list">
                {data.dashboard.alerts.map((alert) => (
                  <li key={alert.message} className={`alert-item ${alert.severity}`}>
                    <span className="alert-dot" />
                    {alert.message}
                  </li>
                ))}
              </ul>
            </div>
          </section>

          <section className="lower-grid">
            <div className="panel">
              <div className="panel-header compact">
                <div>
                  <p className="eyebrow">Designer</p>
                  <h2>Workflow canvas</h2>
                </div>
              </div>
              <div className="workflow-canvas">
                <div className="canvas-node source">Source</div>
                <div className="canvas-node transform">Filter</div>
                <div className="canvas-node transform">Hash</div>
                <div className="canvas-node target">Target</div>
              </div>
            </div>

            <div className="panel">
              <div className="panel-header compact">
                <div>
                  <p className="eyebrow">Connectivity</p>
                  <h2>Connectors</h2>
                </div>
              </div>
              <ul className="connector-list">
                {data.dashboard.connectors.map((connector) => (
                  <li key={connector.name}>
                    <div>
                      <strong>{connector.name}</strong>
                      <small>{connector.type}</small>
                    </div>
                    <div className="connector-right">
                      <span className={`status-badge ${connector.status}`}>{connector.status}</span>
                      <small>{connector.lastSync}</small>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          </section>
        </>
      )
    }

    if (activeView === 'Workflows') {
      return (
        <div className="view-grid">
          <div className="panel">
            <div className="panel-header compact">
              <div>
                <p className="eyebrow">Pipeline design</p>
                <h2>Workflow catalog</h2>
              </div>
            </div>

            <div className="workflow-list">
              {data.workflows.map((workflow) => (
                <button
                  key={workflow.id}
                  type="button"
                  className={workflow.id === selectedWorkflowId ? 'workflow-item selected' : 'workflow-item'}
                  onClick={() => setSelectedWorkflowId(workflow.id)}
                >
                  <div>
                    <strong>{workflow.name}</strong>
                    <small>{workflow.owner}</small>
                  </div>
                  <span className={`status-badge ${workflow.status}`}>{workflow.status}</span>
                  <em>{workflow.steps} steps</em>
                </button>
              ))}
            </div>
          </div>

          <div className="panel workflow-detail-card">
            <div className="panel-header compact">
              <div>
                <p className="eyebrow">Execution detail</p>
                <h2>{workflowDetail?.name ?? 'Workflow detail'}</h2>
              </div>
            </div>

            {workflowDetail && (
              <>
                <div className="workflow-summary">
                  <span>{workflowDetail.owner}</span>
                  <span>{workflowDetail.schedule}</span>
                  <span className={`status-badge ${workflowDetail.status}`}>{workflowDetail.status}</span>
                </div>

                <div className="settings-grid">
                  <label className="setting-box">
                    Source type
                    <select disabled={!canOperate} value={workflowDetail.sourceType ?? 'csv'} onChange={(event) => handleExecutionFieldChange('sourceType', event.target.value)}>
                      <option value="csv">CSV file</option>
                      <option value="jdbc">JDBC query</option>
                      <option value="ldaps">LDAP over TLS</option>
                    </select>
                  </label>
                  {(workflowDetail.sourceType ?? 'csv') === 'jdbc' && (
                    <>
                      <label className="setting-box">
                        Source JDBC URL
                        <input disabled={!canOperate} value={workflowDetail.sourceJdbcUrl ?? ''} onChange={(event) => handleExecutionFieldChange('sourceJdbcUrl', event.target.value)} placeholder="jdbc:postgresql://host:5432/database?sslmode=verify-full" />
                      </label>
                      <label className="setting-box">
                        Source username
                        <input disabled={!canOperate} value={workflowDetail.sourceUsername ?? ''} onChange={(event) => handleExecutionFieldChange('sourceUsername', event.target.value)} />
                      </label>
                      <label className="setting-box">
                        Source password secret ID
                        <input disabled={!canOperate} value={workflowDetail.sourcePasswordSecretId ?? ''} onChange={(event) => handleExecutionFieldChange('sourcePasswordSecretId', event.target.value)} />
                      </label>
                      <label className="setting-box">
                        Read-only SELECT query
                        <textarea disabled={!canOperate} value={workflowDetail.sourceQuery ?? ''} onChange={(event) => handleExecutionFieldChange('sourceQuery', event.target.value)} />
                      </label>
                    </>
                  )}
                  {(workflowDetail.sourceType === 'ldaps' || workflowDetail.sourceType === 'ldap') && (
                    <>
                      <label className="setting-box">
                        LDAPS URL
                        <input disabled={!canOperate} value={workflowDetail.sourceLdapUrl ?? ''} onChange={(event) => handleExecutionFieldChange('sourceLdapUrl', event.target.value)} placeholder="ldaps://directory.example.com:636" />
                      </label>
                      <label className="setting-box">
                        Base DN
                        <input disabled={!canOperate} value={workflowDetail.sourceLdapBaseDn ?? ''} onChange={(event) => handleExecutionFieldChange('sourceLdapBaseDn', event.target.value)} />
                      </label>
                      <label className="setting-box">
                        LDAP search filter
                        <input disabled={!canOperate} value={workflowDetail.sourceLdapFilter ?? ''} onChange={(event) => handleExecutionFieldChange('sourceLdapFilter', event.target.value)} placeholder="(objectClass=person)" />
                      </label>
                      <label className="setting-box">
                        Attributes (comma-separated)
                        <input disabled={!canOperate} value={(workflowDetail.sourceLdapAttributes ?? []).join(',')} onChange={(event) => handleExecutionFieldChange('sourceLdapAttributes', event.target.value.split(',').map((attribute) => attribute.trim()).filter(Boolean))} />
                      </label>
                      <label className="setting-box">
                        Bind DN (optional)
                        <input disabled={!canOperate} value={workflowDetail.sourceLdapBindDn ?? ''} onChange={(event) => handleExecutionFieldChange('sourceLdapBindDn', event.target.value)} />
                      </label>
                      <label className="setting-box">
                        Bind password secret ID
                        <input disabled={!canOperate} value={workflowDetail.sourceLdapPasswordSecretId ?? ''} onChange={(event) => handleExecutionFieldChange('sourceLdapPasswordSecretId', event.target.value)} />
                      </label>
                    </>
                  )}
                  {(workflowDetail.sourceType ?? 'csv') === 'csv' && canOperate && (
                    <label className="setting-box">
                      CSV source {workflowDetail.sourceCsvReady ? '(uploaded)' : '(not uploaded)'}
                      <input type="file" accept=".csv,text/csv" onChange={handleUploadCsv} />
                    </label>
                  )}
                  <label className="setting-box">
                    Target JDBC URL
                    <input disabled={!canOperate} value={workflowDetail.targetJdbcUrl ?? ''} onChange={(event) => handleExecutionFieldChange('targetJdbcUrl', event.target.value)} placeholder="MySQL ?sslMode=VERIFY_IDENTITY or PostgreSQL ?sslmode=verify-full" />
                  </label>
                  <label className="setting-box">
                    Target table
                    <input disabled={!canOperate} value={workflowDetail.targetTable ?? ''} onChange={(event) => handleExecutionFieldChange('targetTable', event.target.value)} placeholder="schema.table" />
                  </label>
                  <label className="setting-box">
                    Target username
                    <input disabled={!canOperate} value={workflowDetail.targetUsername ?? ''} onChange={(event) => handleExecutionFieldChange('targetUsername', event.target.value)} />
                  </label>
                  <label className="setting-box">
                    Key Vault password secret ID
                    <input disabled={!canOperate} value={workflowDetail.targetPasswordSecretId ?? ''} onChange={(event) => handleExecutionFieldChange('targetPasswordSecretId', event.target.value)} />
                  </label>
                </div>

                <div className="workflow-canvas-editor">
                  <div className="toolbox-panel">
                    <p className="eyebrow">Palette</p>
                    {['source', 'filter', 'transform', 'target'].map((type) => (
                      <button
                        key={type}
                        type="button"
                        className="toolbox-button"
                        draggable
                        onDragStart={(event) => handlePaletteDragStart(event, type)}
                        onClick={() => handleAddNode(type)}
                      >
                        Add {type}
                      </button>
                    ))}
                  </div>

                  <div
                    ref={canvasRef}
                    className="canvas-surface"
                    onMouseMove={handleCanvasMouseMove}
                    onMouseUp={handleCanvasMouseUp}
                    onMouseLeave={handleCanvasMouseUp}
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={handleCanvasDrop}
                  >
                    <svg className="edge-layer" viewBox="0 0 1100 420" preserveAspectRatio="none">
                      {canvasEdges.map((edge) => {
                        const fromNode = canvasNodes.find((node) => node.id === edge.from)
                        const toNode = canvasNodes.find((node) => node.id === edge.to)
                        if (!fromNode || !toNode) return null

                        const startX = fromNode.x + 120
                        const startY = fromNode.y + 44
                        const endX = toNode.x
                        const endY = toNode.y + 44

                        return (
                          <path
                            key={edge.id}
                            d={`M ${startX} ${startY} C ${startX + 80} ${startY}, ${endX - 80} ${endY}, ${endX} ${endY}`}
                            fill="none"
                            stroke="rgba(148, 163, 184, 0.8)"
                            strokeWidth="2"
                            strokeDasharray="8 10"
                          />
                        )
                      })}
                    </svg>

                    {canvasNodes.map((node) => (
                      <button
                        key={node.id}
                        type="button"
                        className={node.id === selectedNodeId ? 'canvas-node selected' : 'canvas-node'}
                        style={{ left: `${node.x}px`, top: `${node.y}px` }}
                        onMouseDown={(event) => handleNodeMouseDown(event, node.id)}
                        onClick={(event) => handleNodeClick(node.id, event)}
                      >
                        <span className={`node-chip ${node.type}`}>{node.type}</span>
                        <strong>{node.name}</strong>
                        <small>{node.config}</small>
                      </button>
                    ))}
                  </div>

                  <div className="inspector-panel">
                    <p className="eyebrow">Inspector</p>
                    {selectedNode ? (
                      <>
                        <label>
                          Name
                          <input value={selectedNode.name} onChange={(event) => handleNodeFieldChange('name', event.target.value)} />
                        </label>
                        <label>
                          Config
                          <input value={selectedNode.config} onChange={(event) => handleNodeFieldChange('config', event.target.value)} />
                        </label>
                        <label>
                          Type
                          <select value={selectedNode.type} onChange={(event) => handleNodeFieldChange('type', event.target.value)}>
                            <option value="source">source</option>
                            <option value="filter">filter</option>
                            <option value="transform">transform</option>
                            <option value="target">target</option>
                          </select>
                        </label>
                        <div className="inspector-actions">
                          <button type="button" className="secondary-button" onClick={() => handleNodeClick(selectedNode.id, { shiftKey: true })}>
                            Link node
                          </button>
                          <button type="button" className="danger-button" onClick={handleDeleteSelectedNode}>
                            Delete
                          </button>
                        </div>
                      </>
                    ) : (
                      <p>Select a node.</p>
                    )}
                  </div>
                </div>

                <div className="workflow-editor">
                  <div className="editor-row">
                    <label>
                      Workflow name
                      <input name="name" value={workflowForm.name} onChange={handleWorkflowFieldChange} />
                    </label>
                    <label>
                      Owner
                      <input name="owner" value={workflowForm.owner} onChange={handleWorkflowFieldChange} />
                    </label>
                  </div>

                  <div className="editor-row">
                    <label>
                      Schedule
                      <input name="schedule" value={workflowForm.schedule} onChange={handleWorkflowFieldChange} />
                    </label>
                    <label>
                      Status
                      <select name="status" value={workflowForm.status} onChange={handleWorkflowFieldChange}>
                        <option value="active">active</option>
                        <option value="paused">paused</option>
                        <option value="draft">draft</option>
                      </select>
                    </label>
                  </div>

                  <div className="stage-editor-block">
                    {designerStages.map((stage, index) => (
                      <div key={`${workflowDetail.id}-${stage.name}-${index}`} className="stage-editor-row">
                        <input
                          value={stage.name}
                          onChange={(event) => handleStageFieldChange(index, 'name', event.target.value)}
                        />
                        <input
                          value={stage.records}
                          onChange={(event) => handleStageFieldChange(index, 'records', event.target.value)}
                        />
                        <select
                          value={stage.status}
                          onChange={(event) => handleStageFieldChange(index, 'status', event.target.value)}
                        >
                          <option value="success">success</option>
                          <option value="running">running</option>
                          <option value="queued">queued</option>
                        </select>
                      </div>
                    ))}
                  </div>

                  <div className="editor-actions">
                    {canOperate && (
                      <>
                        <button type="button" className="secondary-button" onClick={handleAddStage}>Add stage</button>
                        <button type="button" className="primary-button" onClick={handleSaveWorkflow}>Save workflow</button>
                      </>
                    )}
                  </div>

                  {workflowMessage && <div className="status-banner">{workflowMessage}</div>}
                </div>

                <div className="stage-list">
                  {designerStages.map((stage, index) => (
                    <div key={`${workflowDetail.id}-${stage.name}-${index}`} className="stage-item">
                      <div>
                        <strong>{stage.name}</strong>
                        <small>{stage.records} records</small>
                      </div>
                      <div className="stage-meta">
                        <span className={`status-badge ${stage.status}`}>{stage.status}</span>
                        <small>{stage.duration}</small>
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      )
    }

    if (activeView === 'Connectors') {
      return (
        <div className="panel">
          <div className="panel-header compact">
            <div>
              <p className="eyebrow">Integration</p>
              <h2>Connector health</h2>
            </div>
          </div>

          <ul className="connector-list expanded">
            {data.connectors.map((connector) => (
              <li key={connector.name}>
                <div>
                  <strong>{connector.name}</strong>
                  <small>{connector.type}</small>
                </div>
                <div className="connector-right">
                  <span className={`status-badge ${connector.status}`}>{connector.status}</span>
                  <small>{connector.lastSync}</small>
                </div>
              </li>
            ))}
          </ul>
        </div>
      )
    }

    if (activeView === 'Security') {
      return (
        <div className="view-grid">
          <div className="panel">
            <div className="panel-header compact">
              <div>
                <p className="eyebrow">Security</p>
                <h2>Control policies</h2>
              </div>
            </div>

            <div className="security-stack">
              {data.security.controls.map((control) => (
                <div key={control.name} className="security-item">
                  <strong>{control.name}</strong>
                  <small>{control.owner}</small>
                  <span className={`status-badge ${control.status}`}>{control.status}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="panel">
            <div className="panel-header compact">
              <div>
                <p className="eyebrow">Connectors</p>
                <h2>Policy checks</h2>
              </div>
            </div>

            <div className="security-stack">
              {data.security.connectors.map((entry) => (
                <div key={entry.name} className="security-item">
                  <strong>{entry.name}</strong>
                  <small>{entry.policy}</small>
                </div>
              ))}
            </div>
          </div>
        </div>
      )
    }

    if (activeView === 'Mapping') {
      return (
        <div className="mapping-layout">
          <div className="panel">
            <div className="panel-header compact">
              <div>
                <p className="eyebrow">Schema</p>
                <h2>Source fields</h2>
              </div>
            </div>

            <ul className="schema-list">
              {[...new Set(designerMappings.map((mapping) => mapping.sourceField).filter(Boolean))].map((field) => (
                <li key={field}>{field}</li>
              ))}
            </ul>
          </div>

          <div className="panel wide-panel">
            <div className="panel-header compact">
              <div>
                <p className="eyebrow">Transformation</p>
                <h2>Mapping rules</h2>
              </div>
            </div>

            <table className="mapping-table">
              <thead>
                <tr>
                  <th>Source</th>
                  <th>Target</th>
                  <th>Privacy action</th>
                  <th>Field transform</th>
                  <th>Privacy</th>
                </tr>
              </thead>
              <tbody>
                {designerMappings.map((mapping, index) => (
                  <tr key={`${index}-${mapping.sourceField}-${mapping.targetField}`}>
                    <td><input disabled={!canOperate} value={mapping.sourceField ?? ''} onChange={(event) => handleMappingChange(index, 'sourceField', event.target.value)} /></td>
                    <td><input disabled={!canOperate} value={mapping.targetField ?? ''} onChange={(event) => handleMappingChange(index, 'targetField', event.target.value)} /></td>
                    <td>
                      <select disabled={!canOperate} value={mapping.privacyAction ?? 'none'} onChange={(event) => handleMappingChange(index, 'privacyAction', event.target.value)}>
                        <option value="none">none</option>
                        <option value="sha256">SHA-256</option>
                        <option value="mask">mask</option>
                        <option value="encrypt">vault encryption</option>
                        <option value="drop">drop</option>
                      </select>
                    </td>
                    <td>
                      <select
                        disabled={!canOperate}
                        value={(mapping.transformations ?? [])[0] ?? ''}
                        onChange={(event) => handleMappingChange(index, 'transformations', event.target.value ? [event.target.value] : [])}
                      >
                        <option value="">none</option>
                        <option value="trim">trim whitespace</option>
                        <option value="lowercase">lowercase</option>
                        <option value="uppercase">uppercase</option>
                      </select>
                    </td>
                    <td>{['sha256', 'mask', 'encrypt'].includes(mapping.privacyAction) ? 'protected' : 'standard'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {canOperate && (
              <div className="editor-actions">
                <button type="button" className="secondary-button" onClick={handleAddMapping}>Add mapping</button>
                <button type="button" className="primary-button" onClick={handleSaveWorkflow}>Save mappings</button>
              </div>
            )}
          </div>

          <div className="panel">
            <div className="panel-header compact">
              <div>
                <p className="eyebrow">Privacy</p>
                <h2>Validation</h2>
              </div>
            </div>

            <div className="privacy-stack">
              <div className="privacy-card">
                <strong>Masking</strong>
                <span>Enabled</span>
              </div>
              <div className="privacy-card">
                <strong>Hashing</strong>
                <span>Required for email</span>
              </div>
              <div className="privacy-card">
                <strong>Regex</strong>
                <span>Department code validation</span>
              </div>
            </div>

            <div className="preview-box">
              <h3>Live preview</h3>
              <table className="mini-table">
                <thead>
                  <tr>
                    <th>full_name</th>
                    <th>email_hash</th>
                    <th>department_code</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Jane Doe</td>
                    <td>9a9d7e...</td>
                    <td>HR</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )
    }

    if (activeView === 'KeyVault') {
      return (
        <div className="panel">
          <div className="panel-header compact">
            <div>
              <p className="eyebrow">Secrets</p>
              <h2>KeyVault</h2>
            </div>
          </div>

          <table className="mapping-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Status</th>
                <th>Value</th>
              </tr>
            </thead>
            <tbody>
              {data.keyvault.map((entry) => (
                <tr key={entry.name}>
                  <td>{entry.name}</td>
                  <td>{entry.type}</td>
                  <td><span className={`status-badge ${entry.status}`}>{entry.status}</span></td>
                  <td>{entry.value}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )
    }

    if (activeView === 'Audit Logs') {
      return (
        <div className="panel">
          <div className="panel-header compact">
            <div>
              <p className="eyebrow">Compliance</p>
              <h2>Activity audit</h2>
            </div>
          </div>

          <ul className="audit-list">
            {data.audit.map((entry) => (
              <li key={`${entry.time}-${entry.event}`}>
                <strong>{entry.time}</strong>
                <span>{entry.actor}</span>
                <em>{entry.event}</em>
              </li>
            ))}
          </ul>
        </div>
      )
    }

    return (
      <div className="panel">
        <div className="panel-header compact">
          <div>
            <p className="eyebrow">Administration</p>
            <h2>Settings</h2>
          </div>
        </div>

        <div className="settings-grid">
          <div className="setting-box">
            <h3>Security baseline</h3>
            <p>LDAPS + AES-256 vault validation is enabled.</p>
          </div>
          <div className="setting-box">
            <h3>Processing mode</h3>
            <p>Adaptive streaming and chunking is set to auto.</p>
          </div>
          <div className="setting-box">
            <h3>Data privacy</h3>
            <p>Hashing and masking policies are active for PII fields.</p>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-block">
          <div className="brand-mark">N</div>
          <div>
            <h1>DataSifter</h1>
            <span>Control plane</span>
          </div>
        </div>

        <nav className="nav-menu">
          {visibleNavItems.map((item) => (
            <button
              key={item}
              type="button"
              className={item === activeView ? 'nav-item active' : 'nav-item'}
              onClick={() => setActiveView(item)}
            >
              {item}
            </button>
          ))}
        </nav>

        <div className="sidebar-card">
          <p className="eyebrow">System health</p>
          <strong>{data.dashboard.status}</strong>
          <span>All critical sync paths are operational.</span>
        </div>
      </aside>

      <main className="main-panel">
        <header className="topbar">
          <div className="search-box">
            <span>⌕</span>
            <input type="text" value="Search pipelines, connectors, jobs" readOnly />
          </div>
          <div className="topbar-actions">
            <div className="user-pill">
              <span className="avatar">{(session?.username ?? 'U').slice(0, 2).toUpperCase()}</span>
              <div>
                <strong>{session?.username}</strong>
                <small>{session?.roles?.join(', ')}</small>
              </div>
            </div>
            <button type="button" className="secondary-button" onClick={handleLogout}>Sign out</button>
          </div>
        </header>

        {dataError && <div className="status-banner error">{dataError}</div>}
        {renderView()}
      </main>
    </div>
  )
}

export default App
